package com.examhelper.core

/**
 * 本地题库检索：把识别出的题目与知识库条款做关键词匹配打分。
 *
 * 思路（无需分词库）：
 *  1. 对题干清洗，去掉无意义的疑问词/连接词（按「最长匹配」整体删除，不再逐字比较）；
 *  2. 抽取题干中的 n-gram（2/3/4 字）作为候选词，字面越长权重越高；
 *  3. 对每条条款统计命中词的数量与权重，叠加来源（制度名/章名）加成；
 *  4. 返回得分最高的若干条。
 */
object QuestionMatcher {

    /**
     * 常见无意义词（疑问词、连接词、指代词、标点上下文）。
     *
     * 重要：**否定词与情态词绝不能放进这里**。
     * 删掉「不」会让「属于」和「不属于」清洗成同一个串，造成语义相反的题 1.0 假命中；
     * 「不得 / 应当 / 必须」这类词同理，删了判断题就分不清对错方向。
     */
    private val STOP_WORDS = setOf(
        "下列", "以下", "关于", "根据", "按照", "属于", "哪些", "什么", "怎么", "如何",
        "为什么", "哪个", "多少", "是否", "正确", "错误", "说法", "表述", "规定",
        "的", "了", "是", "在", "和", "与",
        "或", "及", "对", "中", "该", "其", "我", "你", "他", "她", "它",
        "都", "也", "还", "就", "而", "被", "把", "请", "则", "以", "于",
        "（", "）", "(", ")", "：", ":", "？", "?", "，", ",", "。", ".", "；", ";",
        "、", "“", "”", "‘", "’", "【", "】", "《", "》", " ", "　"
    )

    /** 按长度降序，保证「下列」优先于「下」这类后缀，实现最长匹配 */
    private val STOP_WORDS_BY_LEN: List<String> = STOP_WORDS.sortedByDescending { it.length }

    /**
     * 检索主入口。
     * @param question 识别出的题干
     * @param kb 知识库
     * @param topN 返回条数
     */
    fun match(question: String, kb: KnowledgeBase, topN: Int = 4): List<MatchResult> {
        val q = clean(question)
        if (q.isEmpty()) return emptyList()

        val terms = extractTerms(q)
        if (terms.isEmpty()) return emptyList()

        val all = KnowledgeBaseLoader.flatten(kb)
        val scored = all.map { art ->
            var score = 0f
            for ((term, weight) in terms) {
                if (art.articleText.contains(term)) {
                    score += weight
                }
            }
            // 制度名 / 章名命中加成（命中说明问题指向该制度）
            for ((term, weight) in terms) {
                if (term.length >= 3 && (art.document.contains(term) || art.chapter.contains(term))) {
                    score += weight * 0.6f
                }
            }
            art.copy(score = score)
        }.filter { it.score > 0f }
            .sortedByDescending { it.score }

        // 去除内容完全重复的条款
        val seen = HashSet<String>()
        val result = ArrayList<MatchResult>()
        for (r in scored) {
            val key = r.articleText.take(24)
            if (seen.add(key)) result.add(r)
            if (result.size >= topN) break
        }
        return result
    }

    /**
     * 清洗题干：按「最长匹配」整体删除停用词与标点。
     * 旧实现逐字符与词表比较，多字词（"下列"/"关于"…）永远匹配不到，等于失效；
     * 现在按词表从长到短做前缀匹配，命中就整体跳过。
     */
    fun clean(text: String): String {
        if (text.isEmpty()) return text
        val sb = StringBuilder(text.length)
        var i = 0
        scan@ while (i < text.length) {
            for (w in STOP_WORDS_BY_LEN) {
                if (text.startsWith(w, i)) {
                    i += w.length
                    continue@scan
                }
            }
            sb.append(text[i])
            i++
        }
        return sb.toString()
    }

    /**
     * 归一化文本用于"句子是否同一句"的判断：
     * 全角转半角、统一小写、去掉空白与标点，**保留全部汉字（含否定词/情态词）**。
     */
    fun normalize(text: String): String {
        if (text.isEmpty()) return text
        val sb = StringBuilder(text.length)
        for (raw in text) {
            val ch = when {
                raw in '\uFF01'..'\uFF5E' -> (raw.code - 0xFEE0).toChar()   // 全角 ASCII → 半角
                else -> raw
            }
            if (ch.isWhitespace() || !ch.isLetterOrDigit()) continue
            sb.append(ch.lowercaseChar())
        }
        return sb.toString()
    }

    /** 抽取候选词：2/3/4 字 n-gram，长词权重更高 */
    private fun extractTerms(q: String): List<Pair<String, Float>> {
        val terms = LinkedHashMap<String, Float>()
        val n = q.length
        if (n < 2) return listOf(q to 1f)

        for (len in intArrayOf(6, 5, 4, 3, 2)) {
            if (n < len) continue
            for (i in 0..n - len) {
                val g = q.substring(i, i + len)
                // 跳过含数字的组合（数字单独处理），保留实体词
                if (g.any { it.isDigit() }) continue
                terms[g] = (terms[g] ?: 0f) + len.toFloat()
            }
        }

        // 数字实体（如 "6000元"、"4年"、"3个月"、"两周"）
        val numRegex = Regex("\\d+\\s*(元|年|个月|月|周|天|日|小时|分|学分|次|名|%)")
        for (m in numRegex.findAll(q)) {
            terms[m.value] = 3f
        }

        // 全题作为最长短语（若较短）
        if (n in 5..24) {
            terms[q] = 6f
        }

        return terms.entries.map { it.key to it.value }
            .sortedByDescending { it.second }
    }
}
