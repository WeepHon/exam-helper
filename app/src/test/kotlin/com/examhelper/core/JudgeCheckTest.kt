package com.examhelper.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 判断题「条文核对」回归测试：
 *  - 题干与条文一致 → 可确认「对」
 *  - 题库答案过时/错误 → 提示「按条文应为对」，且不改变题库答案本身
 *  - 题干数字与条文不符 → 判为「错」
 *  - 非判断题 / 无条文 → 不显示核对
 */
class JudgeCheckTest {

    private val clause = "本科学生国家奖学金奖励标准为每生每年10000元（发放金额依据财政部、教育部、人力资源社会保障部2024年印发相关政策文件执行）。"

    private fun art(text: String = clause, no: String = "第五条") =
        MatchResult("某大学本科学生奖学金实施办法", "第二章 奖励对象、标准", no, text, 0.9f)

    @Test
    fun `题干与条文一致时给出肯定核对`() {
        val r = JudgeCheck.check("本科学生国家奖学金的奖励标准为每生每年10000元", "对", "", listOf(art()))
        requireNotNull(r)
        assertEquals("对", r.expected)
        assertEquals(true, r.consistent)
    }

    @Test
    fun `题库答案与条文冲突时提示按条文应为对`() {
        val r = JudgeCheck.check("本科学生国家奖学金的奖励标准为每生每年10000元", "错", "", listOf(art()))
        requireNotNull(r)
        assertEquals("对", r.expected)
        assertEquals(false, r.consistent)
        assertEquals("错", r.bankAnswer)
    }

    @Test
    fun `题干数字与条文不同时按条文判错`() {
        val r = JudgeCheck.check("本科学生国家奖学金的奖励标准为每生每年8000元", "对", "", listOf(art()))
        requireNotNull(r)
        assertEquals("错", r.expected)
        assertEquals(false, r.consistent)
    }

    @Test
    fun `OCR 漏字仍能核对`() {
        val r = JudgeCheck.check("本科学生国家奖学金的奖励标准为每生年10000元", "错", "", listOf(art()))
        requireNotNull(r)
        assertEquals("对", r.expected)
    }

    @Test
    fun `字母答案按选项内容判断选择题型`() {
        val r = JudgeCheck.check(
            "本科学生国家奖学金的奖励标准为每生每年10000元", "B",
            "A.对\nB.错", listOf(art())
        )
        requireNotNull(r)
        assertEquals("对", r.expected)
        assertEquals(false, r.consistent)
    }

    @Test
    fun `非判断题不显示核对`() {
        assertNull(JudgeCheck.check("下列情形中不得解除劳动合同", "C", "A.甲\nB.乙\nC.丙\nD.丁", listOf(art())))
    }

    @Test
    fun `条文无关时不显示核对`() {
        assertNull(JudgeCheck.check("学校设有下列哪些奖学金", "对", "", listOf(art())))
    }

    @Test
    fun `只差否定词时按条文判错`() {
        val clause2 = "申请缓考学生经批准参加下学期开学前补考的，其考试成绩按期末考试处理。学生未经批准不参加学校组织的课程期末考试的，以旷考论，成绩以零分记，旷考课程不能补考，只能重修。"
        val art2 = MatchResult("某大学全日制普通本科学生学籍管理办法", "第五章 成绩考核", "第十四条", clause2, 0.9f)
        val stmt = "学生未经批准不参加学校组织的课程期末考试的,以旷考论,成绩以零分记,旷考课程能补考,也能重学"
        val r = JudgeCheck.check(stmt, "错", "", listOf(art2))
        requireNotNull(r)
        assertEquals("错", r.expected)
        assertEquals(true, r.consistent)
    }

    @Test
    fun `与条文一致的否定表述判对`() {
        val clause2 = "学生未经批准不参加学校组织的课程期末考试的，以旷考论，成绩以零分记，旷考课程不能补考，只能重修。"
        val art2 = MatchResult("某大学全日制普通本科学生学籍管理办法", "第五章 成绩考核", "第十四条", clause2, 0.9f)
        val r = JudgeCheck.check("学生未经批准不参加学校组织的课程期末考试的，以旷考论，成绩以零分记，旷考课程不能补考，只能重修", "对", "", listOf(art2))
        requireNotNull(r)
        assertEquals("对", r.expected)
        assertEquals(true, r.consistent)
    }

    @Test
    fun `没有命中条文时不显示核对`() {
        assertNull(JudgeCheck.check("本科学生国家奖学金的奖励标准为每生每年10000元", "对", "", emptyList()))
    }
}
