package com.examhelper.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 从 assets/knowledge_base.json 加载知识库（懒加载单例）。
 */
object KnowledgeBaseLoader {

    @Volatile
    private var instance: KnowledgeBase? = null

    fun get(context: Context): KnowledgeBase {
        instance?.let { return it }
        synchronized(this) {
            instance?.let { return it }
            val kb = load(context)
            instance = kb
            return kb
        }
    }

    private fun load(context: Context): KnowledgeBase {
        val text = context.assets.open("knowledge_base.json")
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }

        val root = JSONObject(text)
        val categories = ArrayList<Category>()
        val docs = ArrayList<Document>()

        val catArr = root.optJSONArray("categories")
        if (catArr != null) {
            for (i in 0 until catArr.length()) {
                val c = catArr.getJSONObject(i)
                val refs = ArrayList<DocRef>()
                val dArr = c.optJSONArray("docs")
                if (dArr != null) {
                    for (j in 0 until dArr.length()) {
                        val d = dArr.getJSONObject(j)
                        refs.add(DocRef(d.optInt("no"), d.optString("title")))
                    }
                }
                categories.add(Category(c.optString("name"), refs))
            }
        }

        val docArr = root.optJSONArray("documents")
        if (docArr != null) {
            for (i in 0 until docArr.length()) {
                val d = docArr.getJSONObject(i)
                val chapters = ArrayList<Chapter>()
                val chArr = d.optJSONArray("chapters")
                if (chArr != null) {
                    for (j in 0 until chArr.length()) {
                        val ch = chArr.getJSONObject(j)
                        val arts = ArrayList<Article>()
                        val aArr = ch.optJSONArray("articles")
                        if (aArr != null) {
                            for (k in 0 until aArr.length()) {
                                val a = aArr.getJSONObject(k)
                                arts.add(Article(a.optString("no"), a.optString("text")))
                            }
                        }
                        chapters.add(Chapter(ch.optString("title"), arts))
                    }
                }
                docs.add(Document(d.optString("title"), chapters))
            }
        }

        return KnowledgeBase(categories, docs)
    }

    /** 统计知识库条文总数（真实值，供界面显示，避免硬编码失真） */
    fun articleCount(kb: KnowledgeBase): Int =
        kb.documents.sumOf { d -> d.chapters.sumOf { it.articles.size } }

    /** 扁平化所有条款，方便检索 */
    fun flatten(kb: KnowledgeBase): List<MatchResult> {
        val list = ArrayList<MatchResult>()
        for (doc in kb.documents) {
            for (ch in doc.chapters) {
                for (a in ch.articles) {
                    list.add(
                        MatchResult(
                            document = doc.title,
                            chapter = ch.title,
                            articleNo = a.no,
                            articleText = a.text,
                            score = 0f
                        )
                    )
                }
            }
        }
        return list
    }
}
