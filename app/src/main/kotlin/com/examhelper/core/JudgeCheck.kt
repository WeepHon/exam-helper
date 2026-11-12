package com.examhelper.core

/**
 * 判断题「条文核对」：用本地条文校验导入题库给出的判断答案。
 *
 * 设计约束：
 *  - 只做「核对提示」，**不覆盖**题库答案（题库答案可能是考试系统的评分口径，改判有丢分风险）；
 *  - 纯逻辑、无 Android 依赖，便于单元测试；
 *  - 只在已经匹配出的少数条文上做字符串比较（Levenshtein，几十次短串运算），微秒级开销，不读盘、不联网。
 *
 * 判定方式：
 *  - 题干与某条条文句子的编辑距离相似度 ≥ [MATCH_SIM] → 视为「题干出自条文」；
 *  - 此时若题干与条文只差数字 → 按条文判「错」，否则判「对」。
 */
object JudgeCheck {

    /** 题干与条文句子视为「同一句话」的最低编辑距离相似度（容忍 OCR 漏字、多字） */
    private const val MATCH_SIM = 0.86f

    /** 去掉数字后仍需达到的相似度，才确认「只差数字」 */
    private const val STRIP_SIM = 0.90f

    /** 核对结果 */
    data class Result(
        val bankAnswer: String,   // 题库答案原文
        val expected: String,     // 按条文推断的答案："对" / "错"
        val citation: String,     // 条文出处，如 《…》第五条
        val evidence: String,     // 条文句子（已截断）
        val consistent: Boolean   // 与题库答案是否一致
    ) {
        /** 面板里展示的一行文字 */
        fun describe(): String {
            return if (consistent) {
                "条文核对：与${citation}表述一致，可确认「${expected}」"
            } else {
                "条文核对：${citation}为「${evidence}」，按条文该说法应为「${expected}」，与题库答案「${bankAnswer}」不一致"
            }
        }
    }

    /**
     * @return null 表示不是判断题、没有可用条文，或条文证据不足（此时不显示核对行）
     */
    fun check(question: String, answer: String, options: String, matches: List<MatchResult>): Result? {
        if (matches.isEmpty()) return null
        val bankSide = judgeSide(answer, options) ?: return null
        val stmt = normalize(question)
        if (stmt.length < 6) return null

        var bestText = ""
        var bestCite = ""
        var bestSim = 0f
        for (m in matches) {
            for (s in sentences(m.articleText)) {
                for (v in variants(s)) {
                    if (v.isEmpty()) continue
                    val sim = editSimilarity(stmt, v)
                    if (sim > bestSim) {
                        bestSim = sim
                        bestText = v
                        bestCite = citationOf(m)
                    }
                }
            }
        }
        if (bestSim < MATCH_SIM || bestText.isEmpty()) return null

        val expected = if (isFalseStatement(stmt, bestText) || isNegationMismatch(stmt, bestText)) "错" else "对"
        val consistent = (expected == "对") == bankSide
        return Result(
            bankAnswer = answer.trim(),
            expected = expected,
            citation = bestCite,
            evidence = truncate(originalSentence(bestText), 60),
            consistent = consistent
        )
    }

    /** 推断「题干与条文矛盾（只差关键数字）」→ 该说法为假 */
    private fun isFalseStatement(stmt: String, sentence: String): Boolean {
        val sd = digits(stmt)
        val td = digits(sentence)
        if (sd.isEmpty() || td.isEmpty()) return false
        if (td.containsAll(sd)) return false   // 条文里的数字覆盖了题干（如多写了年份），不算矛盾
        if (sd == td) return false
        val a = stripDigits(stmt)
        val b = stripDigits(sentence)
        if (a.isEmpty() || b.isEmpty()) return false
        return editSimilarity(a, b) >= STRIP_SIM
    }

    /**
     * 题干与条文只差否定词（「能补考」vs「不能补考」、「可」vs「不得」）时，两者表达相反，
     * 按条文该说法为「错」。OCR 正常时这一条最容易被漏判——它既不是数字差异，也不是无关条文。
     */
    private fun isNegationMismatch(stmt: String, sentence: String): Boolean {
        val ns = negationCount(stmt)
        val nd = negationCount(sentence)
        if (ns == nd) return false
        val a = stripNegation(stmt)
        val b = stripNegation(sentence)
        if (a.isEmpty() || b.isEmpty()) return false
        return editSimilarity(a, b) >= STRIP_SIM
    }

    private fun negationCount(s: String): Int {
        var t = s
        var c = 0
        for (w in NEG_WORDS) {
            c += Regex(w).findAll(t).count()
            t = t.replace(w, "")
        }
        return c + t.count { NEG_CHARS.indexOf(it) >= 0 }
    }

    private fun stripNegation(s: String): String {
        var t = s
        for (w in NEG_WORDS) t = t.replace(w, "")
        return t.filter { NEG_CHARS.indexOf(it) < 0 }
    }

    /** 从「答案 + 选项」判断这是不是判断题；返回 true 表示题库答案为「对」 */
    fun judgeSide(answer: String, options: String): Boolean? {
        val a = normalize(answer)
        if (a.isEmpty()) return null
        if (options.isNotBlank() && isJudgeOptions(options)) {
            val letter = Regex("[a-h]").find(a)?.value?.firstOrNull()
            if (letter != null) {
                val text = optionText(letter, options) ?: return null
                return judgeText(text)
            }
        }
        if (a.length > 4) return null
        return judgeText(a)
    }

    private fun judgeText(s: String): Boolean? {
        val t = s.lowercase()
        if (t.contains("错") || t.contains("×") || t.contains("否") || t == "f" || t == "false" || t == "x") return false
        if (t.contains("对") || t.contains("√") || t.contains("是") || t == "t" || t == "true") return true
        return null
    }

    private fun isJudgeOptions(options: String): Boolean =
        options.contains("对") || options.contains("错") || options.contains("√") || options.contains("×")

    /** 取出某个选项字母后的选项文本（如 "A.对" → "对"） */
    private fun optionText(letter: Char, options: String): String? {
        val re = Regex("(?i)([a-h])\\s*[.、．:：)）]\\s*")
        val all = re.findAll(options).toList()
        for ((i, m) in all.withIndex()) {
            if (m.groupValues[1].first().lowercaseChar() != letter) continue
            val start = (m.range.last + 1).coerceAtMost(options.length)
            val end = if (i + 1 < all.size) all[i + 1].range.first.coerceAtMost(options.length) else options.length
            if (start > end) return null
            return options.substring(start, end)
        }
        return null
    }

    /** 否定词：题干与条文只差否定词时，两者语义相反，按条文该说法为「错」 */
    private val NEG_WORDS = listOf("不得", "不能", "禁止", "严禁", "无需", "无须", "不准")
    private const val NEG_CHARS = "不没未无"

    private val SENTENCE_SPLIT = Regex("[。；;\\n]")
    private val DIGITS = Regex("\\d+")
    private val PARENS = Regex("（[^）]*）|\\([^)]*\\)")

    private fun sentences(text: String): List<String> = text.split(SENTENCE_SPLIT)
        .map { it.trim() }
        .filter { normalize(it).length >= 6 }

    /** 同一句的多种写法：原句、去括号、括号前部分（条文常带括号补充说明） */
    private fun variants(sentence: String): List<String> {
        val list = LinkedHashSet<String>()
        list.add(normalize(sentence))
        val noParen = sentence.replace(PARENS, "")
        list.add(normalize(noParen))
        val beforeParen = sentence.substringBefore('（').substringBefore('(')
        list.add(normalize(beforeParen))
        return list.filter { it.isNotEmpty() }
    }

    /** 保留原句用于证据展示（优先去括号版本，字数更短更清楚） */
    private fun originalSentence(normalized: String): String {
        // normalized 已丢失原始标点，这里直接用归一化文本作为证据即可，阅读性足够
        return normalized
    }

    private fun digits(s: String): Set<String> = DIGITS.findAll(s).map { it.value }.toSet()

    private fun stripDigits(s: String): String = s.replace(DIGITS, "")

    private fun citationOf(m: MatchResult): String {
        val doc = m.document.trim()
        val no = m.articleNo.trim()
        return if (doc.isEmpty()) no else "《${doc}》${no}"
    }

    private fun truncate(s: String, max: Int): String =
        if (s.length <= max) s else s.take(max - 1) + "…"

    /** 编辑距离相似度：1 - 距离/较长串长度 */
    fun editSimilarity(a: String, b: String): Float {
        if (a == b) return 1f
        if (a.isEmpty() || b.isEmpty()) return 0f
        val dist = editDistance(a, b)
        val maxLen = maxOf(a.length, b.length)
        return (1f - dist.toFloat() / maxLen).coerceIn(0f, 1f)
    }

    private fun editDistance(a: String, b: String): Int {
        val n = a.length
        val m = b.length
        var prev = IntArray(m + 1) { it }
        var cur = IntArray(m + 1)
        for (i in 1..n) {
            cur[0] = i
            for (j in 1..m) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[m]
    }

    /** 归一化：去掉题号/标签/标点空白，统一大小写（题干可能带 "[1分]【规章制度】" 前缀） */
    private fun normalize(raw: String): String {
        var s = raw.trim()
        s = s.replace(Regex("^\\s*\\[[^\\]]{0,8}\\]\\s*"), "")
        s = s.replace(Regex("^\\s*【[^】]{0,10}】\\s*"), "")
        s = s.replace(Regex("^\\s*\\d{1,4}\\s*[.、．,，)）:：]\\s*"), "")
        return s.lowercase()
            .replace(Regex("[\\u3000\\s]"), "")
            .replace(Regex("[^\\p{L}\\p{Nd}]"), "")
    }
}
