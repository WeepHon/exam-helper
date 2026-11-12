package com.examhelper.importer

import android.content.Context
import com.examhelper.core.Article
import com.examhelper.core.Chapter
import com.examhelper.core.Document
import com.examhelper.core.LogUtil
import com.examhelper.core.QaItem
import org.json.JSONArray
import org.json.JSONObject

/**
 * 导入题库的本地存储（app 私有目录，无需权限）。
 *
 * 支持【同时启用多个本地题库】：
 *   - files/imported/index.json          题库索引（每个题库一条元数据：id/名称/类型/题数/是否启用）
 *   - files/imported/banks/<id>.json     每个题库的独立内容文件
 *
 * 兼容迁移：旧版（v1.4.0）单文件 imported/qa.json、imported/kb.json 若存在，
 * 会在首次读取索引时自动迁移为两个独立题库条目。
 */
object ImportStore {

    private const val DIR = "imported"
    private const val FILE_INDEX = "imported/index.json"
    private const val BANK_DIR = "imported/banks"

    // 旧版单文件（仅用于迁移）
    private const val FILE_LEGACY_QA = "imported/qa.json"
    private const val FILE_LEGACY_KB = "imported/kb.json"

    /** 一个已导入题库的元数据 */
    data class ImportedBank(
        val id: String,
        val name: String,
        val type: String,        // "qa" 问答 / "kb" 条文
        val itemCount: Int,
        val enabled: Boolean,
        val createdAt: Long
    )

    // ---------- 索引 ----------

    private fun indexFile(context: Context) = java.io.File(context.filesDir, FILE_INDEX)

    /** 读取题库索引（带旧版迁移） */
    @Synchronized
    fun list(context: Context): List<ImportedBank> {
        migrateLegacyIfNeeded(context)
        val f = indexFile(context)
        if (!f.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText(Charsets.UTF_8))
            val out = ArrayList<ImportedBank>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(ImportedBank(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    type = o.optString("type"),
                    itemCount = o.optInt("itemCount"),
                    enabled = o.optBoolean("enabled", true),
                    createdAt = o.optLong("createdAt")
                ))
            }
            out
        }.getOrDefault(emptyList())
    }

    @Synchronized
    private fun saveIndex(context: Context, banks: List<ImportedBank>) {
        val arr = JSONArray()
        for (b in banks) {
            arr.put(JSONObject()
                .put("id", b.id)
                .put("name", b.name)
                .put("type", b.type)
                .put("itemCount", b.itemCount)
                .put("enabled", b.enabled)
                .put("createdAt", b.createdAt))
        }
        val f = indexFile(context)
        f.parentFile?.mkdirs()
        f.writeText(arr.toString(), Charsets.UTF_8)
    }

    // ---------- 增删改 ----------

    /** 新增一个题库（独立文件 + 更新索引），返回其元数据 */
    @Synchronized
    fun add(
        context: Context,
        name: String,
        type: String,
        qaItems: List<QaItem>,
        documents: List<Document>
    ): ImportedBank {
        val id = System.currentTimeMillis().toString() + "-" +
            (Math.random() * 10000).toInt().toString()
        val banksDir = java.io.File(context.filesDir, BANK_DIR)
        banksDir.mkdirs()

        val content = JSONObject().put("type", type)
        val itemCount: Int
        if (type == "qa") {
            val arr = JSONArray()
            for (it in qaItems) {
                arr.put(JSONObject()
                    .put("question", it.question)
                    .put("answer", it.answer)
                    .put("options", it.options))
            }
            content.put("items", arr)
            itemCount = qaItems.size
        } else {
            val docArr = JSONArray()
            for (doc in documents) {
                val chArr = JSONArray()
                for (ch in doc.chapters) {
                    val aArr = JSONArray()
                    for (a in ch.articles) {
                        aArr.put(JSONObject().put("no", a.no).put("text", a.text))
                    }
                    chArr.put(JSONObject().put("title", ch.title).put("articles", aArr))
                }
                docArr.put(JSONObject().put("title", doc.title).put("chapters", chArr))
            }
            content.put("documents", docArr)
            itemCount = documents.sumOf { d -> d.chapters.sumOf { it.articles.size } }
        }
        java.io.File(banksDir, "$id.json").writeText(content.toString(), Charsets.UTF_8)

        val bank = ImportedBank(
            id = id,
            name = name.ifBlank { "未命名题库" },
            type = type,
            itemCount = itemCount,
            enabled = true,
            createdAt = System.currentTimeMillis()
        )
        val banks = list(context).toMutableList()
        banks.add(bank)
        saveIndex(context, banks)
        LogUtil.i("ImportStore.add: id=$id name=${bank.name} type=$type count=$itemCount")
        return bank
    }

    /** 删除一个题库 */
    @Synchronized
    fun remove(context: Context, id: String) {
        runCatching { java.io.File(context.filesDir, "$BANK_DIR/$id.json").delete() }
        val banks = list(context).filter { it.id != id }
        saveIndex(context, banks)
        LogUtil.i("ImportStore.remove: id=$id")
    }

    /** 切换某个题库的启用状态 */
    @Synchronized
    fun setEnabled(context: Context, id: String, enabled: Boolean) {
        val banks = list(context).map { if (it.id == id) it.copy(enabled = enabled) else it }
        saveIndex(context, banks)
        LogUtil.i("ImportStore.setEnabled: id=$id enabled=$enabled")
    }

    // ---------- 读取已启用内容（合并） ----------

    /** 是否存在任何【启用】的题库（问答或条文）。只读索引，不加载内容。 */
    @Synchronized
    fun hasEnabled(context: Context): Boolean =
        list(context).any { it.enabled }

    /** 合并所有【启用】的问答对题库 */
    @Synchronized
    fun loadEnabledQa(context: Context): List<QaItem> {
        val out = ArrayList<QaItem>()
        for (b in list(context)) {
            if (b.type != "qa" || !b.enabled) continue
            out.addAll(loadQaOf(context, b.id))
        }
        return out
    }

    /** 合并所有【启用】的条文题库 */
    @Synchronized
    fun loadEnabledKb(context: Context): List<Document> {
        val out = ArrayList<Document>()
        for (b in list(context)) {
            if (b.type != "kb" || !b.enabled) continue
            out.addAll(loadKbOf(context, b.id))
        }
        return out
    }

    private fun loadQaOf(context: Context, id: String): List<QaItem> {
        val f = java.io.File(context.filesDir, "$BANK_DIR/$id.json")
        if (!f.exists()) return emptyList()
        return runCatching {
            val root = JSONObject(f.readText(Charsets.UTF_8))
            val arr = root.optJSONArray("items") ?: JSONArray()
            val list = ArrayList<QaItem>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(QaItem(
                    question = o.optString("question"),
                    answer = o.optString("answer"),
                    options = o.optString("options")
                ))
            }
            list
        }.getOrDefault(emptyList())
    }

    private fun loadKbOf(context: Context, id: String): List<Document> {
        val f = java.io.File(context.filesDir, "$BANK_DIR/$id.json")
        if (!f.exists()) return emptyList()
        return runCatching {
            val root = JSONObject(f.readText(Charsets.UTF_8))
            val docs = ArrayList<Document>()
            val docArr = root.optJSONArray("documents") ?: JSONArray()
            for (i in 0 until docArr.length()) {
                val d = docArr.getJSONObject(i)
                val chapters = ArrayList<Chapter>()
                val chArr = d.optJSONArray("chapters") ?: JSONArray()
                for (j in 0 until chArr.length()) {
                    val ch = chArr.getJSONObject(j)
                    val arts = ArrayList<Article>()
                    val aArr = ch.optJSONArray("articles") ?: JSONArray()
                    for (k in 0 until aArr.length()) {
                        val a = aArr.getJSONObject(k)
                        arts.add(Article(a.optString("no"), a.optString("text")))
                    }
                    chapters.add(Chapter(ch.optString("title"), arts))
                }
                docs.add(Document(d.optString("title"), chapters))
            }
            docs
        }.getOrDefault(emptyList())
    }

    // ---------- 清空 ----------

    @Synchronized
    fun clear(context: Context) {
        runCatching { java.io.File(context.filesDir, DIR).deleteRecursively() }
        LogUtil.i("ImportStore.clear")
    }

    // ---------- 汇总 ----------

    fun summary(context: Context): String {
        val banks = list(context)
        if (banks.isEmpty()) return "未导入题库"
        val enabledCount = banks.count { it.enabled }
        val qaCount = banks.filter { it.type == "qa" }.sumOf { it.itemCount }
        val kbCount = banks.filter { it.type == "kb" }.sumOf { it.itemCount }
        val parts = ArrayList<String>()
        if (qaCount > 0) parts.add("问答 $qaCount 题")
        if (kbCount > 0) parts.add("条文 $kbCount 条")
        return "已导入 ${banks.size} 个题库（启用 $enabledCount 个）：" + parts.joinToString("，")
    }

    // ---------- 旧版迁移 ----------

    private fun migrateLegacyIfNeeded(context: Context) {
        val idx = indexFile(context)
        if (idx.exists()) return

        val legacyQa = java.io.File(context.filesDir, FILE_LEGACY_QA)
        val legacyKb = java.io.File(context.filesDir, FILE_LEGACY_KB)
        if (!legacyQa.exists() && !legacyKb.exists()) return

        LogUtil.i("ImportStore: 检测到旧版单文件题库，开始迁移")
        // 先写空索引占位：add() 内部会调用 list()，进而回到本函数；
        // 若此时 index.json 尚不存在且 legacy 文件未删，会导致无限递归（StackOverflow）。
        idx.parentFile?.mkdirs()
        idx.writeText("[]", Charsets.UTF_8)

        var migrated = 0

        if (legacyQa.exists()) {
            val ok = runCatching {
                val arr = JSONArray(legacyQa.readText(Charsets.UTF_8))
                val items = ArrayList<QaItem>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    items.add(QaItem(
                        question = o.optString("question"),
                        answer = o.optString("answer"),
                        options = o.optString("options")
                    ))
                }
                if (items.isNotEmpty()) {
                    add(context, "旧版导入题库", "qa", items, emptyList())
                    true
                } else false
            }.getOrDefault(false)
            if (ok) {
                migrated++
                runCatching { legacyQa.delete() }
            }
        }
        if (legacyKb.exists()) {
            val ok = runCatching {
                val root = JSONObject(legacyKb.readText(Charsets.UTF_8))
                val docs = ArrayList<Document>()
                val docArr = root.optJSONArray("documents") ?: JSONArray()
                for (i in 0 until docArr.length()) {
                    val d = docArr.getJSONObject(i)
                    val chapters = ArrayList<Chapter>()
                    val chArr = d.optJSONArray("chapters") ?: JSONArray()
                    for (j in 0 until chArr.length()) {
                        val ch = chArr.getJSONObject(j)
                        val arts = ArrayList<Article>()
                        val aArr = ch.optJSONArray("articles") ?: JSONArray()
                        for (k in 0 until aArr.length()) {
                            val a = aArr.getJSONObject(k)
                            arts.add(Article(a.optString("no"), a.optString("text")))
                        }
                        chapters.add(Chapter(ch.optString("title"), arts))
                    }
                    docs.add(Document(d.optString("title"), chapters))
                }
                if (docs.isNotEmpty()) {
                    add(context, "旧版条文题库", "kb", emptyList(), docs)
                    true
                } else false
            }.getOrDefault(false)
            if (ok) {
                migrated++
                runCatching { legacyKb.delete() }
            }
        }
        LogUtil.i("ImportStore: 迁移完成，共 $migrated 个题库")
    }
}
