package com.examhelper.core

/**
 * 知识库数据结构
 */
data class Article(
    val no: String,          // 条款号，如 "第一条" / "问1"
    var text: String         // 条款正文
)

data class Chapter(
    val title: String,       // 章标题，如 "第一章 总则"
    val articles: MutableList<Article> = mutableListOf()
)

data class Document(
    val title: String,       // 制度名称
    val chapters: MutableList<Chapter> = mutableListOf()
)

data class Category(
    val name: String,        // 分类，如 "教学管理制度"
    val docs: List<DocRef>
)

data class DocRef(
    val no: Int,
    val title: String
)

data class KnowledgeBase(
    val categories: List<Category>,
    val documents: List<Document>
)

/**
 * 一次匹配的结果：命中的条款 + 得分
 */
data class MatchResult(
    val document: String,
    val chapter: String,
    val articleNo: String,
    val articleText: String,
    val score: Float
)

/**
 * 一次完整的回答：本地命中 + 双路 LLM（题库约束作答 / 自由联网作答）
 */
data class AnswerResult(
    val question: String,
    val localMatches: List<MatchResult>,
    val kbAnswer: String? = null,   // 依据本地题库的 LLM 回答
    val webAnswer: String? = null,  // 自由联网（模型自身知识）回答
    val source: String = "本地题库",
    val qaMatches: List<QaItem> = emptyList(),  // 导入题库精确命中的问答对
    val qaCheck: JudgeCheck.Result? = null  // 判断题条文核对（仅提示，不覆盖题库答案）
)

/**
 * 导入题库中的一道「题目 + 答案」问答对
 */
data class QaItem(
    val question: String,
    val answer: String,
    val options: String = ""
)
