package com.examhelper.core

import android.content.Context
import com.examhelper.importer.ImportStore
import com.examhelper.search.LlmClient

/**
 * 答题管线：本地题库匹配 + （可选）联网 LLM 生成。
 *
 * 关键设计：本地题库是内部专用内容（网上搜不到），因此联网 LLM 不是"自由发挥"，
 * 而是被强制要求【只依据本地题库条款作答】。为了让它答得准，这里会把：
 *   1) n-gram 匹配出的 top 条款；
 *   2) top1 命中所属章节的完整条款；
 * 一并作为上下文喂给模型。
 */
object AnswerEngine {

    /** 从一段屏幕/控件文本中抽取"题干"（取最像题目的句子） */
    fun extractQuestion(text: String): String {
        val lines = text.split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        // 1) 优先取含问号的行（要求有一定长度，避免误取 "？" 碎片）
        val q = lines.firstOrNull { (it.contains('？') || it.contains('?')) && it.length >= 4 }
        if (q != null) return cleanupStem(q)

        // 2) 填空题：题干含空白括号（"（）"/"()" 常被 OCR 拆成行尾 "("、"（" 或行首 ")"、"）"）。
        //    用「全局重构」：过滤掉选项行、UI 噪声行、短碎片后，把题号行正文提为开头，
        //    再按原顺序拼接其余题干正文行——OCR 经常把选项、状态词、题号混在断行之间，
        //    局部向前/向后合并会漏拼或误拼，全局过滤后再拼最稳。
        val hasParen = lines.any { it.contains('（') || it.contains('(') || it.contains('）') || it.contains(')') }
        if (hasParen) {
            var qnoBody = ""
            val content = ArrayList<String>()
            for (l in lines) {
                if (isOptionLine(l) || isNoiseLine(l)) continue
                val qno = QNO_PREFIX_RE.find(l)
                if (qno != null && cjkCount(l.substring(qno.range.last + 1)) > 0) {
                    qnoBody = l.substring(qno.range.last + 1)
                    continue
                }
                // 短碎片（不含括号且中文过短）多为 UI 残留（"屏暮共"、"题"、"美团"等）
                val hasP = l.contains('（') || l.contains('(') || l.contains('）') || l.contains(')')
                if (!hasP && cjkCount(l) < 5) continue
                content.add(l)
            }
            val stem = qnoBody + content.joinToString("")
            if (stem.isNotBlank()) return cleanupStem(stem)
        }

        // 3) 题型标签行不应作为题干，先剔除
        val contentLines = lines.filter { l ->
            TYPE_TAGS.none { t -> l.contains(t) && l.length <= t.length + 2 }
        }

        // 4) 兜底：取最长的实质行（通常是题干）
        val pool = if (contentLines.isNotEmpty()) contentLines else lines
        val longest = pool.maxByOrNull { it.length } ?: text.trim()
        return cleanupStem(longest)
    }

    /** 数字题号前缀："25、" "26." "3）" "6," 等（OCR 常把 "、" 识别成 ","） */
    private val QNO_PREFIX_RE = Regex("^\\d{1,4}\\s*[.、．,，)）:：]\\s*")

    /** 开头的元信息标签："[1分]" "【规章制度】" "[1分)" 等（题库题干里不存在，属噪声） */
    private val LEAD_TAG_RE = Regex("^\\s*[\\[【][^\\]】)）]{0,12}[\\]】)）]\\s*")

    /** 选项行（A/B/C/D 开头，OCR 常识别成 "OA." / "O A." / "OA学生" 等，分隔符可能缺失） */
    private val OPTION_RE = Regex("^[O0]?[A-Ha-h][\\s.、．:：)）\\u4e00-\\u9fff]")

    /** UI 噪声词（状态条/按钮/外卖通知等），出现在行内即视为噪声行 */
    private val NOISE_WORDS = listOf(
        "屏幕共享", "共享中", "在线考试", "考试倒计时", "交卷", "上一题", "下一题",
        "取货中", "送达", "美团", "识别中", "正在识别"
    )

    /** 噪声行前缀（OCR 可能只识别出这些词的一部分） */
    private val NOISE_STARTS = listOf("屏幕", "在线", "共享", "倒计时", "取货", "送达", "美团", "ID")

    private val TYPE_TAGS = arrayOf("单选题", "多选题", "判断题", "填空题", "简答题", "选择题")

    /** 中文字符个数 */
    private fun cjkCount(s: String): Int = s.count { it in '\u4e00'..'\u9fff' }

    /** 是否选项行 */
    private fun isOptionLine(line: String): Boolean = OPTION_RE.containsMatchIn(line.trim())

    /** 是否 UI 噪声行 */
    private fun isNoiseLine(line: String): Boolean {
        val l = line.trim()
        if (l.isEmpty()) return true
        for (t in TYPE_TAGS) if (l.contains(t) && l.length <= t.length + 2) return true
        for (w in NOISE_WORDS) if (l.contains(w)) return true
        for (w in NOISE_STARTS) if (l.startsWith(w)) return true
        if (Regex("^\\d{1,2}:\\d{2}$").matches(l)) return true
        if (Regex("^\\d{1,4}$").matches(l)) return true
        if (Regex("^\\d+\\s*[|/]\\s*\\d+").matches(l)) return true
        if (Regex("^第\\s*\\d+\\s*题").containsMatchIn(l)) return true
        if (cjkCount(l) == 0) return true
        if (l.none { it.isLetterOrDigit() } && !l.any { it in "（）()" }) return true
        return false
    }

    /**
     * 清洗抽出的题干：去掉题号前缀与开头元信息标签，截断尾部混入的 UI 噪声
     * （题号进度、页码、ID、状态词、选项残留），提升与题库题干的相似度。
     */
    private fun cleanupStem(raw: String): String {
        var s = raw.trim()
        var changed = true
        while (changed && s.isNotEmpty()) {
            changed = false
            val before = s
            s = s.replaceFirst(QNO_PREFIX_RE, "").trimStart()
            s = s.replaceFirst(LEAD_TAG_RE, "").trimStart()
            if (s != before) changed = true
        }
        s = truncateTailNoise(s)
        return s.trim('，', '。', '、', ';', '；', ':', '：', '-', '|', '/', ' ')
    }

    /** 截断题干尾部混入的 UI 噪声，返回截断后的正文 */
    private fun truncateTailNoise(s: String): String {
        var pos = s.length
        fun cutAt(i: Int) { if (i in 0 until pos) pos = i }

        // 数字进度：如 "4| 100" "3/ 100" "13 /100" "1/100"
        Regex("\\d+\\s*[|/]\\s*\\d+").find(s)?.let { cutAt(it.range.first) }
        // 考试/学号 ID
        Regex("ID\\s*\\d*").find(s)?.let { cutAt(it.range.first) }
        // 状态词 / 按钮 / 通知残留
        for (w in NOISE_WORDS) {
            val idx = s.indexOf(w)
            if (idx >= 0) cutAt(idx)
        }
        // 选项残留（题干末尾混入的 "A.xx" "B.xx"，题干正文不含）
        Regex("[A-Ha-h]\\s*[.、．:：)）]").find(s)?.let { cutAt(it.range.first) }
        return s.substring(0, pos)
    }

    /** 本地检索 */
    fun searchLocal(context: Context, question: String, topN: Int = 8): List<MatchResult> {
        val kb = KnowledgeBaseLoader.get(context)
        return QuestionMatcher.match(question, kb, topN)
    }

    /** 组装本地答案文本 */
    fun formatLocal(matches: List<MatchResult>): String {
        if (matches.isEmpty()) {
            return "本地题库未找到匹配条款。\n\n建议：在 App 主界面配置「联网 AI」后，可结合本地题库内容给出更准确的答案。"
        }
        val sb = StringBuilder()
        matches.forEachIndexed { i, m ->
            sb.append("【${i + 1}】")
            if (m.document.isNotEmpty()) sb.append(m.document).append(" · ")
            if (m.chapter.isNotEmpty() && m.chapter != "（无章节）" && m.chapter != "（守则）" && m.chapter != "（问答）")
                sb.append(m.chapter).append(" · ")
            sb.append(m.articleNo).append('\n')
            sb.append(m.articleText).append("\n\n")
        }
        return sb.toString().trimEnd()
    }

    /**
     * 构建"喂给模型的本地题库上下文"。
     *
     * 本地题库是内部专用、网上搜不到的内容，模型必须依据它作答。
     * 因此这里不仅给出 top 命中的条款，还把 top1 命中所属章节的完整条款
     * 一并附上，给模型提供充分、自洽的判断依据。
     *
     * @param maxArticles 单章补充的条款数上限，避免上下文过长拖慢生成
     */
    fun buildLocalContext(matches: List<MatchResult>, kb: KnowledgeBase, maxArticles: Int = 8): String {
        if (matches.isEmpty()) return "（未命中任何相关条款）"

        val ordered = LinkedHashMap<String, MatchResult>()

        // 1) top 命中条款优先
        for (m in matches) {
            ordered.putIfAbsent("${m.document}|${m.chapter}|${m.articleNo}", m)
        }

        // 2) top1 命中章节的完整条款（若章节不算太大），提供完整判断依据
        val top = matches.first()
        run findChapter@{
            for (doc in kb.documents) {
                if (doc.title != top.document) continue
                for (ch in doc.chapters) {
                    if (ch.title != top.chapter) continue
                    if (ch.articles.size <= maxArticles) {
                        for (a in ch.articles) {
                            ordered.putIfAbsent(
                                "${doc.title}|${ch.title}|${a.no}",
                                MatchResult(doc.title, ch.title, a.no, a.text, 0f)
                            )
                        }
                    }
                    return@findChapter
                }
            }
        }

        val sb = StringBuilder()
        ordered.values.forEachIndexed { i, m ->
            val src = StringBuilder("《").append(m.document).append("》")
            if (m.chapter.isNotBlank() && m.chapter !in setOf("（无章节）", "（守则）", "（问答）")) {
                src.append(" ").append(m.chapter)
            }
            sb.append("${i + 1}. [").append(src).append("] ").append(m.articleNo).append("：")
                .append(m.articleText).append('\n')
        }
        return sb.toString()
    }

    /**
     * 完整答题（可挂起）。
     * @param llm 联网配置，为 null 时仅本地检索
     */
    suspend fun answer(
        context: Context,
        rawText: String,
        llm: LlmConfig?
    ): AnswerResult {
        val question = extractQuestion(rawText)
        LogUtil.i("识别原文(${rawText.length}字): ${rawText.take(120)}")
        LogUtil.i("抽取题干: $question")

        // 是否有任何本地题库启用（内置 + 导入），决定是否做本地检索、是否附本地条款上下文
        val builtinEnabled = Config.isBuiltinEnabled(context)
        val hasLocalBank = builtinEnabled || ImportStore.hasEnabled(context)

        // 本地题库全部停用 → 跳过本地检索，直接联网（速度最快，也不再打印本地匹配日志）
        if (!hasLocalBank) {
            LogUtil.i("本地题库全部停用，跳过本地检索，仅联网作答")
            var webAnswer: String? = null
            if (llm != null && llm.isValid()) {
                webAnswer = LlmClient.askUnified(
                    baseUrl = llm.baseUrl,
                    apiKey = llm.apiKey,
                    model = llm.model,
                    question = question,
                    fullText = rawText,
                    localContext = "（无相关条款）"
                )
                LogUtil.i("联网作答返回: ${webAnswer?.take(60) ?: "（失败/空）"}")
            } else {
                LogUtil.i("未配置联网 LLM，且无本地题库，无法作答")
            }
            return AnswerResult(
                question = question,
                localMatches = emptyList(),
                kbAnswer = null,
                webAnswer = webAnswer,
                source = if (webAnswer != null) "联网" else "无",
                qaMatches = emptyList()
            )
        }

        // 1) 导入的「题目+答案」题库匹配：用 bigram Dice 相似度（对 OCR 错字/断行/前后噪声鲁棒）
        //    判定高置信命中。只有「最高分达到阈值且显著领先次高分」才直接采用，
        //    避免 n-gram 撞词的低分相似题误命中；否则丢弃交给 LLM 兜底。
        val qaItems = ImportStore.loadEnabledQa(context)
        val qaMatches = if (qaItems.isNotEmpty()) {
            val scored = QaMatcher.matchScored(question, rawText, qaItems)
            val best = scored.firstOrNull()
            // 判定逻辑抽到 QaConfidence（纯函数，可单测）：
            // 必须领先次高分，或（强置信 + 题干能区分两题）才采用；否则交给条文/LLM 兜底
            val confirmed = QaConfidence.decide(question, scored)
            val confident = if (confirmed != null) listOf(confirmed) else emptyList()
            LogUtil.i(
                "导入问答题库高置信命中 ${confident.size} 条（启用题库 ${qaItems.size} 题，最高分=${
                    if (best == null) "无" else "%.2f".format(best.second)
                }）"
            )
            confident
        } else emptyList()

        // 2) 条文检索：内置知识库（可开关） + 所有已启用的导入条文库合并
        val builtin = KnowledgeBaseLoader.get(context)
        val importedKb = ImportStore.loadEnabledKb(context)
        val mergedDocs = ArrayList<Document>()
        if (builtinEnabled) mergedDocs.addAll(builtin.documents)
        mergedDocs.addAll(importedKb)
        val kb = KnowledgeBase(builtin.categories, mergedDocs)
        val local = QuestionMatcher.match(question, kb, 8)
        LogUtil.i("本地匹配命中 ${local.size} 条（内置${if (builtinEnabled) "启用" else "停用"}+导入条文${importedKb.size}篇）")

        // 2.5) 高置信命中 → 直接返回本地答案，跳过 LLM（速度最快，答案最准）
        if (qaMatches.isNotEmpty()) {
            // 条文核对：只做提示，不覆盖题库答案（题库答案可能是考试系统的评分口径）
            val qaCheck = qaMatches.firstOrNull()?.let {
                JudgeCheck.check(it.question, it.answer, it.options, local)
            }
            if (qaCheck != null) {
                LogUtil.i("条文核对：题库=${qaCheck.bankAnswer}，条文推断=${qaCheck.expected}，一致=${qaCheck.consistent}")
            }
            LogUtil.i("问答高置信命中，跳过 LLM 直接返回本地答案")
            return AnswerResult(
                question = question,
                localMatches = local,
                kbAnswer = null,
                webAnswer = null,
                source = "本地题库",
                qaMatches = qaMatches,
                qaCheck = qaCheck
            )
        }

        // 3) 单次 LLM 作答：本地命中条款作为参考依据，模型自行决定「依据条款」还是「用知识」。
        //    每题只发 1 次请求（速度最快，天然兼容「并发上限=1」的账号），且不依赖任何阈值/判定。
        var webAnswer: String? = null
        if (llm != null && llm.isValid()) {
            val localContext = buildLocalContext(local, kb)
            LogUtil.i("单次作答（本地命中 ${local.size} 条作为参考）, model=${llm.model}")
            webAnswer = LlmClient.askUnified(
                baseUrl = llm.baseUrl,
                apiKey = llm.apiKey,
                model = llm.model,
                question = question,
                fullText = rawText,
                localContext = localContext
            )
            LogUtil.i("作答返回: ${webAnswer?.take(60) ?: "（失败/空）"}")
        } else {
            LogUtil.i("未配置联网 LLM，仅本地检索")
        }
        val source = if (webAnswer != null) "本地题库 + 联网" else "本地题库"
        return AnswerResult(
            question = question,
            localMatches = local,
            kbAnswer = null,
            webAnswer = webAnswer,
            source = source,
            qaMatches = qaMatches
        )
    }

    /**
     * 判断「依据本地题库」一路返回的答案是否其实判定为"题库外"（模型按 prompt 要求
     * 会回答「本题不属于本地题库范围」）。此时应改走联网自由回答兜底。
     * 仅当返回里没有给出具体「答案」时才视为题库外，避免误伤正常作答。
     */
    private fun looksOutOfScope(answer: String): Boolean {
        val a = answer.trim()
        if (a.contains("答案")) return false
        return listOf("不属于", "无关", "未涉及", "未提及", "无依据", "没有相关", "未找到", "超出")
            .any { a.contains(it) }
    }
}

/** 联网（LLM）配置 */
data class LlmConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val name: String = ""   // 配置名（用于多模型区分，可为空）
) {
    fun isValid() = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()
}
