package com.examhelper.core

/**
 * 「问答库命中是否可信」的唯一判定入口（纯逻辑，便于单元测试）。
 *
 * 规则（按顺序）：
 *   1. 没有任何候选、或最高分低于 [QaMatcher.HIGH_CONFIDENCE] → 不采用；
 *   2. 只有一个候选 → 分数达到强置信才采用（避免"题库里只有这一题"时被低分拉走）；
 *   3. 最高分显著领先次高分（>= [LEADER_RATIO] 倍）：
 *        - 最高分达到强置信 → 采用；
 *        - 否则要求次高分本身也低于候选下限（即"一枝独秀"且没有像样的第二名）；
 *   4. 否则视为"接近并列"（如「上午/下午第一节课」这类仅个别字不同的题）：
 *      只有屏幕/题干原文能明确区分二者（[distinguishes]）且最高分达到强置信时才采用，
 *      否则放弃，交给条文检索 + LLM 兜底，宁可不算也不答错。
 */
object QaConfidence {

    /** 需要达到次高分的倍数才算「显著领先」 */
    const val LEADER_RATIO = 1.15f

    /** 区分性片段的最大长度（超过则说明两题差异过大，不适合做片段判断） */
    private const val MAX_DISTINCT_LEN = 24

    /**
     * @param question 抽取出的题干（原文，未归一化）
     * @param scored   [QaMatcher.matchScored] 的返回，已按分数降序
     */
    fun decide(question: String, scored: List<Pair<QaItem, Float>>): QaItem? {
        val best = scored.firstOrNull() ?: return null
        if (best.second < QaMatcher.HIGH_CONFIDENCE) return null
        if (scored.size == 1) {
            return if (best.second >= QaMatcher.STRONG_CONFIDENCE) best.first else null
        }

        val second = scored[1]
        val leads = best.second >= second.second * LEADER_RATIO
        if (best.second >= QaMatcher.STRONG_CONFIDENCE) {
            if (leads) return best.first
            if (distinguishes(question, best.first.question, second.first.question)) return best.first
            return null
        }
        return if (leads && second.second < QaMatcher.HIGH_CONFIDENCE) best.first else null
    }

    /**
     * 题干原文是否只包含 a 的区分性片段、不包含 b 的。
     *
     * 典型场景：题库同时有「上午第一节课…」和「下午第一节课…」，
     * 屏幕上是「下午…」，两题 Dice 分数接近（都可能超过强置信阈值），
     * 此时用两题差异片段在题干里是否出现来裁决。
     */
    fun distinguishes(question: String, a: String, b: String): Boolean {
        val q = QuestionMatcher.normalize(question)
        val aq = QuestionMatcher.normalize(a)
        val bq = QuestionMatcher.normalize(b)
        if (q.isEmpty() || aq.isEmpty() || bq.isEmpty() || aq == bq) return false

        val prefix = commonPrefix(aq, bq)
        val suffix = commonSuffix(aq, bq, prefix)
        val da = aq.substring(prefix, aq.length - suffix)
        val db = bq.substring(prefix, bq.length - suffix)
        if (da.isEmpty() || db.isEmpty()) return false
        if (da.length > MAX_DISTINCT_LEN || db.length > MAX_DISTINCT_LEN) return false
        return q.contains(da) && !q.contains(db)
    }

    private fun commonPrefix(a: String, b: String): Int {
        val n = minOf(a.length, b.length)
        var i = 0
        while (i < n && a[i] == b[i]) i++
        return i
    }

    private fun commonSuffix(a: String, b: String, prefix: Int): Int {
        var i = 0
        while (i < a.length - prefix && i < b.length - prefix &&
            a[a.length - 1 - i] == b[b.length - 1 - i]
        ) i++
        return i
    }
}
