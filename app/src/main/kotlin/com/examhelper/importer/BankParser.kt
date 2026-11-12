package com.examhelper.importer

import com.examhelper.core.Article
import com.examhelper.core.Chapter
import com.examhelper.core.Document
import com.examhelper.core.LogUtil
import com.examhelper.core.QaItem

/**
 * 把提取出的文本自动识别为「条文/教材/知识点类」或「题目答案类」，并结构化。
 *
 * 类型判定策略（分层启发式 + 可选 AI 视觉/文本辅助，兼容混合使用场景）：
 *   1) 外部 AI 判定（视觉/文本）若有效则优先采用；
 *   2) 出现 ≥3 条「第X条」→ 判 kb（规章制度/条文教材，最可靠）；
 *   3) 有 A/B/C/D 选项行或较多「答案」行 → 判 qa（题目题库，答案形式明确）；
 *   4) 有「第X章/节/单元/课/部分/篇」章节结构或中文序号清单 → 判 kb（教材/知识点）；
 *   5) 兜底：无明确「题目+答案」形式时默认判 kb（教材/知识点，避免无关键词教材被误判）。
 */
object BankParser {

    data class ParseResult(
        val type: String,                 // "qa" 或 "kb"
        val qaItems: List<QaItem>,
        val documents: List<Document>
    )

    // 章节/单元/课/篇等结构标题（教材/知识点型），支持中文与阿拉伯数字。
    // 注意：结尾的 .* 必不可少——PDF 提取的条款行通常是「第一条  正文…」，
    // 若正则不含 .*，matches()（整行匹配）会失败，导致条文统计恒为 0。
    private val SECTION_RE = Regex("^第\\s*[一二三四五六七八九十百零0-9]+\\s*[章节单元课部分篇].*")
    private val ARTICLE_RE = Regex("^第\\s*[一二三四五六七八九十百零0-9]+\\s*条.*")
    // 中文序号知识点清单："一、xxx" "二、xxx"（区别于 "第X章" 的层级）
    private val CN_LIST_RE = Regex("^\\s*[一二三四五六七八九十]+\\s*[、.．]\\s*\\S.*")

    // 题号（数字）：分隔符后为题目正文。用宽松匹配以覆盖 "1. 2024年…" 这类以数字开头的题目；
    // 章节号 "1.1" 的误判由 parse() 的启发式判定兜住，不依赖本正则。
    private val QNO_RE = Regex("^\\s*\\d{1,4}\\s*[.、．)）]\\s*(.*)$")
    private val QNO_CN_RE = Regex("^\\s*第\\s*\\d+\\s*题\\s*[.、．:：]?\\s*(.*)$")
    private val OPT_RE = Regex("^\\s*([A-Ha-h])\\s*[.、．:：)）]\\s*(.*)$")
    private val ANS_RE = Regex("^\\s*[【\\[(（]?\\s*(?:答案|参考答案|正确答案|正确选项)\\s*[】\\])）]?\\s*[:：]?\\s*(.*)$")

    fun parse(text: String, title: String, visionType: String? = null): ParseResult {
        val lines = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return ParseResult("qa", emptyList(), emptyList())

        val articleCount = lines.count { ARTICLE_RE.matches(it) }
        val sectionCount = lines.count { SECTION_RE.matches(it) }
        val qnoCount = lines.count { QNO_RE.matches(it) || QNO_CN_RE.matches(it) }
        val optCount = lines.count { OPT_RE.matches(it) }
        val ansCount = lines.count { ANS_RE.matches(it) }
        val cnListCount = lines.count { CN_LIST_RE.matches(it) }

        LogUtil.i(
            "BankParser: 条文=$articleCount 章节=$sectionCount 题号=$qnoCount " +
                "选项=$optCount 答案=$ansCount 中文序号=$cnListCount 总行=${lines.size}"
        )

        // 视觉/AI 辅助判定：若外部提供了判定结果，优先采用（视觉"看"比正则"猜"更准），
        // 但仍以本地启发式作为兜底（AI 结果无效时走原逻辑）。
        val isKb = when {
            visionType == "kb" -> true
            visionType == "qa" -> false
            // 分层判定（由强到弱）：
            //   1) 出现 ≥3 条「第X条」→ 几乎必是规章制度/条文教材，直接判 kb；
            //       （教材目录里常有 "1．2．3．" 数字编号，不能当题号信号）
            //   2) 否则有选项行或较多「答案」行 → 判 qa（题目题库，答案形式明确）；
            //   3) 否则有章节结构或中文序号清单 → 判 kb（教材/知识点）；
            //   4) 兜底：无明确「题目+答案」形式 → 默认判 kb（教材/知识点）。
            //       （教材类常无关键词，若兜底判 qa 会大面积误判）
            articleCount > 2 -> true
            optCount >= 3 || ansCount >= 2 -> false
            sectionCount >= 2 || cnListCount >= 3 -> true
            else -> true
        }

        LogUtil.i(
            "BankParser: 判定为 ${if (isKb) "条文/教材(kb)" else "题目答案(qa)"}" +
                if (visionType != null) "（视觉辅助）" else "（本地启发式）"
        )
        return if (isKb) {
            ParseResult("kb", emptyList(), parseKb(lines, title))
        } else {
            ParseResult("qa", parseQa(lines), emptyList())
        }
    }

    // ---------- 条文 / 教材 / 知识点类 ----------

    private fun parseKb(lines: List<String>, title: String): List<Document> {
        val docs = ArrayList<Document>()
        var curDoc: Document? = null
        var curChap: Chapter? = null
        var curArt: Article? = null
        var paraNo = 0

        fun newDoc() {
            curDoc = Document(title, ArrayList())
            docs.add(curDoc!!)
            curChap = null
            curArt = null
        }

        fun ensureChap(): Chapter {
            val d = curDoc ?: run { newDoc(); curDoc!! }
            val c = curChap
            if (c != null) return c
            val ch = Chapter("（无章节）", ArrayList())
            d.chapters.add(ch)
            curChap = ch
            return ch
        }

        for (s in lines) {
            when {
                SECTION_RE.matches(s) -> {
                    if (curDoc == null) newDoc()
                    val ch = Chapter(s.replace(Regex("\\s+"), " "), ArrayList())
                    curDoc!!.chapters.add(ch)
                    curChap = ch
                    curArt = null
                    paraNo = 0
                }
                ARTICLE_RE.matches(s) -> {
                    val chap = ensureChap()
                    val m = Regex("第\\s*[一二三四五六七八九十百零0-9]+\\s*条").find(s)!!
                    val no = m.value.replace(Regex("\\s+"), "")
                    val rest = s.substring(m.range.last + 1).trim()
                    val art = Article(no, rest)
                    chap.articles.add(art)
                    curArt = art
                    paraNo = 0
                }
                curArt != null -> {
                    // 条文续行，并入上一条正文
                    curArt!!.text += s
                }
                else -> {
                    // 教材/知识点正文段落：作为独立知识点条目入库
                    val chap = ensureChap()
                    paraNo++
                    chap.articles.add(Article(paraNo.toString(), s))
                }
            }
        }
        // 去掉空文档
        return docs.filter { d -> d.chapters.any { it.articles.isNotEmpty() } }
    }

    // ---------- 题目答案类 ----------

    private fun parseQa(lines: List<String>): List<QaItem> {
        val items = ArrayList<QaItem>()
        var q = StringBuilder()
        var opts = StringBuilder()
        var ans = ""

        fun flush() {
            val question = q.toString().trim()
            if (question.isNotEmpty()) {
                items.add(QaItem(question, ans.trim(), opts.toString().trim()))
            }
            q = StringBuilder()
            opts = StringBuilder()
            ans = ""
        }

        for (line in lines) {
            val mQno = QNO_RE.matchEntire(line) ?: QNO_CN_RE.matchEntire(line)
            if (mQno != null) {
                // 新题号行：若之前有题则先落库
                if (q.isNotEmpty()) flush()
                val rest = mQno.groupValues[1].trim()
                if (rest.isNotEmpty()) q.append(rest)
                continue
            }
            val mOpt = OPT_RE.matchEntire(line)
            if (mOpt != null && q.isNotEmpty()) {
                opts.append(line.trim()).append(' ')
                continue
            }
            val mAns = ANS_RE.matchEntire(line)
            if (mAns != null && q.isNotEmpty()) {
                val a = mAns.groupValues[1].trim()
                if (a.isNotEmpty()) ans = a
                continue
            }
            // 普通正文行：并入题干
            if (q.isNotEmpty()) q.append(line.trim())
        }
        if (q.isNotEmpty()) flush()
        return items
    }
}
