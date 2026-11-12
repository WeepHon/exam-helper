package com.examhelper.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 针对 C1–C3 三个 P0 错答缺陷的回归测试：
 *  - C1：包含即 0.95 / 整屏包含即 0.95 的短路
 *  - C2：停用词逐字符比较失效 + 删掉单字「不」导致反义题 1.0 假命中
 *  - C3：0.85 强置信短路绕过「领先次高分」判定
 */
class MatchLogicTest {

    private fun item(q: String, a: String) = QaItem(q, a)
    private fun norm(s: String) = QuestionMatcher.normalize(s)

    // ---------- C2：否定词 ----------

    @Test
    fun `清洗题干时否定词必须保留`() {
        assertEquals("情形不得解除劳动合同", QuestionMatcher.clean("下列情形中不得解除劳动合同"))
        assertTrue(QuestionMatcher.clean("本行为不构成违约").contains("不"))
    }

    @Test
    fun `反义题不得被判成同一题`() {
        val a = item("下列情形中应当解除劳动合同的是", "应当解除")
        val b = item("下列情形中不得解除劳动合同的是", "不得解除")
        val q = "下列情形中不得解除劳动合同的是"
        val best = QaMatcher.matchScored(q, "", listOf(a, b)).first()
        assertEquals("不得解除", best.first.answer)
        assertEquals(1f, best.second, 0.0001f)
    }

    @Test
    fun `归一化保留汉字否定词、去掉标点空白`() {
        assertEquals("不属于a合同", norm("不 属于，A 合同！"))
    }

    // ---------- C1：包含关系不得直接给高分 ----------

    @Test
    fun `短问句被长题干包含不得达到强置信`() {
        val iq = norm("下列说法正确的是：用人单位应当为劳动者缴纳社会保险费")
        val s = QaMatcher.score(norm("下列说法正确的是"), "", iq)
        assertTrue("score=$s", s < QaMatcher.STRONG_CONFIDENCE)
    }

    @Test
    fun `整屏文本包含原题只给中等证据`() {
        val it = item("用人单位应当为劳动者缴纳社会保险费", "A")
        val screen = norm("第1题 用人单位应当为劳动者缴纳社会保险费 A 正确")
        val s = QaMatcher.score(norm("与本屏无关的另一道题"), screen, norm(it.question))
        assertEquals(QaMatcher.SCREEN_CONTAIN_SCORE, s, 0.0001f)
        assertTrue(s < QaMatcher.STRONG_CONFIDENCE)
    }

    @Test
    fun `长度接近且互相包含给包含分`() {
        val q = norm("劳动者提前三十日以书面形式通知用人单位可以解除劳动合同")
        val iq = norm("劳动者提前三十日以书面形式通知用人单位可以解除劳动合同的情形")
        assertEquals(0.84f, QaMatcher.score(q, "", iq), 0.0001f)
    }

    @Test
    fun `完全相同归一化后得满分`() {
        assertEquals(1f, QaMatcher.score(norm("用人单位应当缴纳"), "", norm("用人单位，应当缴纳！")), 0.0001f)
    }

    // ---------- C3：并列高分不得直接采用 ----------

    @Test
    fun `接近并列且题干无法区分时不采用`() {
        val a = item("上午第一节课的任课教师应提前十分钟到岗", "A")
        val b = item("下午第一节课的任课教师应提前十分钟到岗", "B")
        val q = "第一节课的任课教师应提前十分钟到岗"
        assertNull(QaConfidence.decide(q, QaMatcher.matchScored(q, "", listOf(a, b))))
    }

    @Test
    fun `题干能区分近似题时采用正确答案`() {
        val a = item("上午第一节课的任课教师应提前十分钟到岗", "A")
        val b = item("下午第一节课的任课教师应提前十分钟到岗", "B")
        val q = "下午第一节课的任课教师应提前十分钟到岗"
        val decided = QaConfidence.decide(q, QaMatcher.matchScored(q, "", listOf(a, b)))
        assertEquals("B", decided?.answer)
    }

    @Test
    fun `唯一候选低分不采用`() {
        val a = item("用人单位应当为劳动者缴纳社会保险费", "A")
        assertNull(QaConfidence.decide("完全不相干的另一句话在这里", QaMatcher.matchScored("完全不相干的另一句话在这里", "", listOf(a))))
    }

    @Test
    fun `唯一的强置信候选可以采用`() {
        val a = item("用人单位应当为劳动者缴纳社会保险费", "A")
        val q = "用人单位应当为劳动者缴纳社会保险费"
        assertEquals("A", QaConfidence.decide(q, QaMatcher.matchScored(q, "", listOf(a)))?.answer)
    }

    @Test
    fun `显著领先的候选可以采用`() {
        val a = item("用人单位应当为劳动者缴纳社会保险费", "A")
        val b = item("劳动者请假审批流程", "B")
        val q = "用人单位应当为劳动者缴纳社会保险费"
        val scored = QaMatcher.matchScored(q, "", listOf(a, b))
        assertEquals("A", QaConfidence.decide(q, scored)?.answer)
    }
}
