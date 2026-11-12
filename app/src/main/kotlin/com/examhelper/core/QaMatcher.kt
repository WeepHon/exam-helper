package com.examhelper.core

/**
 * 在导入的「题目+答案」题库中检索匹配。
 *
 * 打分策略（对 OCR 识别噪声鲁棒，同时避免"包含即命中"的假阳性）：
 *   1. 归一化后完全相同 → 1.0（唯一的精确命中）；
 *   2. 长度接近且互相包含（OCR 截断 / 多识别了选项）→ 0.84：低于强置信阈值，
 *      必须再通过「显著领先次高分」判定才会被采用；
 *   3. 整屏文本里出现题库原题（题干抽取失败时）→ 0.75：中等证据，同样要过领先性判定；
 *   4. 其余用「字符 bigram Dice 相似度」兜底——屏幕 OCR 常有错字（敦育/教育）、
 *      断行、题号/进度残留，但题目主体与题库几乎一致，Dice 仍会很高；
 *      而无关题即使 n-gram 撞上几个词，整体相似度也很低，能自然过滤掉。
 *
 * 注意：这里**故意不再**让"包含关系"直接给 0.95、让"清洗后相等"直接给 1.0。
 * 旧逻辑会让「下列说法正确的是」这类短问句命中任意长题干（包含即 0.95），
 * 也会在停用词把「不」删掉后把「属于」与「不属于」判成同一题（1.0 假命中）。
 */
object QaMatcher {

    /** 候选下限：低于此分不予考虑 */
    const val HIGH_CONFIDENCE = 0.55f

    /** 强置信：达到即视为确定命中；仅当次高分与它接近时才需要再做区分性校验 */
    const val STRONG_CONFIDENCE = 0.88f

    /** 互相包含时要求的长度比下限（短句被长句包含不算命中） */
    private const val CONTAIN_MIN_RATIO = 0.75f

    /** 互相包含时要求的最短长度 */
    private const val CONTAIN_MIN_LEN = 8

    /** 整屏文本包含题库原题时给的分数（中等证据，低于强置信阈值） */
    const val SCREEN_CONTAIN_SCORE = 0.75f

    private const val SCORE_EXACT = 1f
    private const val SCORE_CONTAIN = 0.84f

    /** 保留 match 以兼容旧调用；内部走带分逻辑 */
    fun match(question: String, fullText: String, items: List<QaItem>, topN: Int = 3): List<QaItem> {
        return matchScored(question, fullText, items).take(topN).map { it.first }
    }

    /**
     * 返回「题目 + 相似度」降序列表（只含得分 > 0 的候选）。
     * 相似度取值 0~1。
     */
    fun matchScored(question: String, fullText: String, items: List<QaItem>): List<Pair<QaItem, Float>> {
        if (items.isEmpty()) return emptyList()
        val q = QuestionMatcher.normalize(question)
        if (q.isEmpty()) return emptyList()
        val screen = QuestionMatcher.normalize(fullText)

        val scored = items.map { item ->
            item to score(q, screen, QuestionMatcher.normalize(item.question))
        }.filter { it.second > 0f }
            .sortedByDescending { it.second }

        // 题目文本高度重合的候选只保留一条，避免同题重复展示
        val seen = HashSet<String>()
        val result = ArrayList<Pair<QaItem, Float>>()
        for (s in scored) {
            if (seen.add(QuestionMatcher.normalize(s.first.question).take(30))) result.add(s)
        }
        return result
    }

    /**
     * 单题打分。三个参数都应先经 [QuestionMatcher.normalize]。
     * 抽成公开纯函数便于单元测试。
     */
    fun score(q: String, screen: String, iq: String): Float {
        if (q.isEmpty() || iq.isEmpty()) return 0f
        if (iq == q) return SCORE_EXACT

        val qShorter = q.length <= iq.length
        val short = if (qShorter) q else iq
        val long = if (qShorter) iq else q
        if (short.length >= CONTAIN_MIN_LEN && long.contains(short) &&
            short.length.toFloat() / long.length >= CONTAIN_MIN_RATIO
        ) {
            return SCORE_CONTAIN
        }

        // 题干抽取失败时，整屏文本里出现了完整原题：给中等证据，由上层再做领先性判定
        if (iq.length >= 12 && screen.contains(iq)) return SCREEN_CONTAIN_SCORE

        return similarity(q, iq)
    }

    /** 字符 bigram Dice 相似度，返回 0~1 */
    fun similarity(a: String, b: String): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        if (a == b) return 1f
        val ga = bigrams(a)
        val gb = bigrams(b)
        if (ga.isEmpty() || gb.isEmpty()) return 0f
        var inter = 0
        for (g in ga) if (gb.contains(g)) inter++
        return 2f * inter / (ga.size + gb.size)
    }

    private fun bigrams(s: String): Set<String> {
        val set = HashSet<String>()
        for (i in 0 until s.length - 1) {
            set.add(s.substring(i, i + 2))
        }
        return set
    }
}
