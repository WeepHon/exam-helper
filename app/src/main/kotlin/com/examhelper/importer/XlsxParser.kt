package com.examhelper.importer

import android.content.Context
import android.net.Uri
import com.examhelper.core.LogUtil
import com.examhelper.core.QaItem
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/**
 * Excel 表格（.xlsx）专用解析：直接把「列式题库表」转成 [QaItem] 列表。
 *
 * 支持常见的列式布局，如：
 *   序号 | 题型 | 类别 | 正确答案 | 题目
 *   序号 | 题目 | A | B | C | D | 答案
 * 通过表头关键词定位「题目」列与「答案」列，不硬编码列号。
 *
 * 为什么单独做：xlsx 是 zip 二进制，走通用文本提取会得到乱码；且表格是「按列」存
 * 题目/答案，纯文本的 BankParser（按行、题号+选项+答案标记）无法识别，只能按列解析。
 */
object XlsxParser {

    /** 解析 xlsx 生成题目答案列表；解析不出任何题时返回空列表 */
    fun parseQa(context: Context, uri: Uri): List<QaItem> {
        val rows = readRows(context, uri)
        if (rows.isEmpty()) {
            LogUtil.i("XlsxParser: 未读取到表格行")
            return emptyList()
        }

        // 1) 定位表头行（含「题目/题干/答案」等关键词）
        var headerIdx = -1
        for ((i, r) in rows.withIndex()) {
            val joined = r.joinToString("")
            if (joined.contains("题目") || joined.contains("题干") ||
                joined.contains("问题") || joined.contains("答案")
            ) {
                headerIdx = i
                break
            }
        }
        if (headerIdx < 0) {
            LogUtil.i("XlsxParser: 未找到表头行")
            return emptyList()
        }
        val header = rows[headerIdx]

        // 2) 定位「题目」列与「答案」列
        val qCol = header.indexOfFirst { h ->
            listOf("题目", "题干", "问题", "试题").any { h.contains(it) }
        }
        val aCol = header.indexOfFirst { h ->
            listOf("答案", "正确").any { h.contains(it) }
        }
        if (qCol < 0 || aCol < 0) {
            LogUtil.i("XlsxParser: 未找到题目/答案列，表头=${header.joinToString("|")}")
            return emptyList()
        }

        // 3) 逐行生成 QaItem
        val items = ArrayList<QaItem>()
        for (row in rows.drop(headerIdx + 1)) {
            val qRaw = row.getOrNull(qCol)?.trim().orEmpty()
            if (qRaw.isEmpty()) continue
            val answer = row.getOrNull(aCol)?.trim().orEmpty()
            val (question, options) = splitQuestionAndOptions(qRaw)
            items.add(QaItem(question = question, answer = answer, options = options))
        }
        LogUtil.i("XlsxParser: 解析出 ${items.size} 道题（题目列=$qCol 答案列=$aCol，总行=${rows.size}）")
        return items
    }

    /**
     * 从题目列文本中切分「题干」与「选项」。
     * 选择类题目通常内嵌 "A.xx  B.xx  C.xx  D.xx"，把它们拆到 options，便于命中后展示对照；
     * 判断题/填空题无选项标记则整体作为题干。
     */
    private fun splitQuestionAndOptions(text: String): Pair<String, String> {
        val m = Regex("[A-Ha-h]\\s*[.、．:：)）]").find(text)
        if (m == null) return text.trim() to ""
        val idx = m.range.first
        if (idx <= 0) return text.trim() to ""
        return text.substring(0, idx).trim() to text.substring(idx).trim()
    }

    // ---------- xlsx(zip + xml) 底层解析 ----------

    /** 读取 xlsx 的所有非空单元格，返回「行 × 列」二维字符串表 */
    private fun readRows(context: Context, uri: Uri): List<List<String>> {
        val bytes = ByteArrayOutputStream()
        context.contentResolver.openInputStream(uri)?.use { input ->
            input.copyTo(bytes)
        }
        val zipBytes = bytes.toByteArray()
        if (zipBytes.isEmpty()) return emptyList()

        var sharedXml: String? = null
        var sheetXml: String? = null
        runCatching {
            ZipInputStream(zipBytes.inputStream()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name
                    when {
                        name == "xl/sharedStrings.xml" ->
                            sharedXml = zip.readBytes().toString(Charsets.UTF_8)
                        // 取第一个工作表（sheet1.xml 优先）
                        sheetXml == null && name.startsWith("xl/worksheets/") && name.endsWith(".xml") ->
                            sheetXml = zip.readBytes().toString(Charsets.UTF_8)
                    }
                    entry = zip.nextEntry
                }
            }
        }.onFailure {
            LogUtil.e("XlsxParser: 解压失败", it)
            return emptyList()
        }

        val shared = sharedXml?.let { parseSharedStrings(it) } ?: emptyList()
        return sheetXml?.let { parseSheetRows(it, shared) } ?: emptyList()
    }

    /** 解析 sharedStrings.xml，返回共享字符串表 */
    private fun parseSharedStrings(xml: String): List<String> {
        val list = ArrayList<String>()
        val siRegex = Regex("<si[^>]*>(.*?)</si>", RegexOption.DOT_MATCHES_ALL)
        val tRegex = Regex("<t[^>]*>(.*?)</t>", RegexOption.DOT_MATCHES_ALL)
        for (m in siRegex.findAll(xml)) {
            val texts = tRegex.findAll(m.groupValues[1])
                .map { decodeXml(it.groupValues[1]) }
            list.add(texts.joinToString(""))
        }
        return list
    }

    /** 解析 sheetN.xml，返回「行 × 列」表（空单元格用空串占位） */
    private fun parseSheetRows(xml: String, shared: List<String>): List<List<String>> {
        val rows = ArrayList<List<String>>()
        val rowRegex = Regex("<row[^>]*>(.*?)</row>", RegexOption.DOT_MATCHES_ALL)
        val cellRegex = Regex("<c\\b[^>]*(?:/>|>.*?</c>)", RegexOption.DOT_MATCHES_ALL)
        val vRegex = Regex("<v[^>]*>(.*?)</v>", RegexOption.DOT_MATCHES_ALL)
        val tRegex = Regex("<t[^>]*>(.*?)</t>", RegexOption.DOT_MATCHES_ALL)

        for (rm in rowRegex.findAll(xml)) {
            val cells = ArrayList<Pair<Int, String>>()
            for (cm in cellRegex.findAll(rm.groupValues[1])) {
                val seg = cm.value
                if (seg.isBlank()) continue
                val ref = Regex("\\br=\"([A-Z]+)\\d*\"").find(seg)?.groupValues?.get(1)
                val type = Regex("\\bt=\"([^\"]+)\"").find(seg)?.groupValues?.get(1)
                val col = if (ref != null) colIndex(ref) else cells.size

                val value = when {
                    seg.endsWith("/>") -> "" // 自闭合空单元格
                    type == "s" -> {
                        val v = vRegex.find(seg)?.groupValues?.get(1)
                        if (v != null) shared.getOrElse(v.trim().toIntOrNull() ?: -1) { "" } else ""
                    }
                    type == "inlineStr" ->
                        decodeXml(tRegex.find(seg)?.groupValues?.get(1) ?: "")
                    else -> {
                        val v = vRegex.find(seg)?.groupValues?.get(1)
                        decodeXml(v ?: "")
                    }
                }
                cells.add(col to value)
            }
            if (cells.isEmpty()) continue
            val maxCol = cells.maxOf { it.first }
            val arr = arrayOfNulls<String>(maxCol + 1)
            for ((c, v) in cells) arr[c] = v
            rows.add(arr.map { it ?: "" })
        }
        return rows
    }

    /** 列引用（如 "A"、"AB"）转 0 基列索引 */
    private fun colIndex(ref: String): Int {
        var n = 0
        for (ch in ref) {
            if (ch in 'A'..'Z') n = n * 26 + (ch - 'A' + 1) else break
        }
        return n - 1
    }

    /** 解 XML 实体 */
    private fun decodeXml(s: String): String {
        return s
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace(Regex("&#(\\d+);")) { it.groupValues[1].toInt().toChar().toString() }
            .replace(Regex("&#x([0-9a-fA-F]+);")) { it.groupValues[1].toInt(16).toChar().toString() }
            .replace("&amp;", "&")
    }
}
