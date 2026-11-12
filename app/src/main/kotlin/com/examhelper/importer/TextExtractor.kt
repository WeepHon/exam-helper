package com.examhelper.importer

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import com.examhelper.core.LogUtil
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.zip.ZipInputStream

/**
 * 从 txt / docx / pdf 文件提取纯文本。
 */
object TextExtractor {

    @Volatile
    private var pdfBoxInited = false

    fun extract(context: Context, uri: Uri): String {
        val name = queryName(context, uri).lowercase()
        LogUtil.i("TextExtractor 开始, name=$name")
        return try {
            when {
                name.endsWith(".docx") -> extractDocx(context, uri)
                name.endsWith(".pdf") -> extractPdf(context, uri)
                else -> extractTxt(context, uri)
            }
        } catch (t: Throwable) {
            LogUtil.e("TextExtractor 提取失败", t)
            throw t
        }
    }

    private fun queryName(context: Context, uri: Uri): String {
        return runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else ""
            } ?: ""
        }.getOrDefault("")
    }

    private fun readBytes(context: Context, uri: Uri): ByteArray {
        val out = ByteArrayOutputStream()
        context.contentResolver.openInputStream(uri)?.use { input ->
            input.copyTo(out)
        }
        return out.toByteArray()
    }

    private fun extractTxt(context: Context, uri: Uri): String {
        val bytes = readBytes(context, uri)
        // 先按 UTF-8；若出现大量替换符则按 GBK 解码
        val utf8 = String(bytes, Charsets.UTF_8)
        val replacementCount = utf8.count { it == '\uFFFD' }
        return if (replacementCount > utf8.length / 20) {
            String(bytes, Charset.forName("GBK"))
        } else {
            utf8
        }
    }

    private fun extractDocx(context: Context, uri: Uri): String {
        val sb = StringBuilder()
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name == "word/document.xml") {
                        val xml = zip.readBytes().toString(Charsets.UTF_8)
                        sb.append(parseDocxXml(xml))
                        break
                    }
                    entry = zip.nextEntry
                }
            }
        }
        return sb.toString()
    }

    /** 解析 docx 的 document.xml：按段落拆分并提取 <w:t> 文本，段间换行 */
    private fun parseDocxXml(xml: String): String {
        val tPattern = Regex("<w:t[^>]*>(.*?)</w:t>")
        val paragraphs = xml.split(Regex("</w:p>"))
        val sb = StringBuilder()
        for (p in paragraphs) {
            val texts = tPattern.findAll(p).joinToString("") { it.groupValues[1] }
            if (texts.isNotEmpty()) {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(texts)
            }
        }
        // 解 XML 实体
        return sb.toString()
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
    }

    private fun extractPdf(context: Context, uri: Uri): String {
        ensurePdfBox(context)
        context.contentResolver.openInputStream(uri)?.use { input ->
            PDDocument.load(input).use { doc ->
                val stripper = PDFTextStripper()
                return stripper.getText(doc)
            }
        }
        return ""
    }

    /** 渲染 PDF 前 maxPages 页为 Bitmap，用于视觉辅助判断题库类型 */
    fun renderPdfPages(context: Context, uri: Uri, maxPages: Int = 2): List<Bitmap> {
        ensurePdfBox(context)
        val list = ArrayList<Bitmap>()
        context.contentResolver.openInputStream(uri)?.use { input ->
            PDDocument.load(input).use { doc ->
                val renderer = PDFRenderer(doc)
                val n = minOf(doc.numberOfPages, maxPages)
                for (i in 0 until n) {
                    runCatching {
                        renderer.renderImage(i, 1.2f)?.let { list.add(it) }
                    }.onFailure {
                        LogUtil.w("渲染 PDF 第 ${i + 1} 页失败: ${it.message}")
                    }
                }
            }
        }
        return list
    }

    /** Bitmap 转 base64 data URL（JPEG，供多模态模型读取） */
    fun bitmapToDataUrl(bitmap: Bitmap): String {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out)
        return "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun ensurePdfBox(context: Context) {
        // pdfbox-android 必须先初始化一次，否则资源文件（glyphlist.txt 等）找不到，
        // PDFTextStripper 会抛 ExceptionInInitializerError / GlyphList not found。
        if (!pdfBoxInited) {
            synchronized(this) {
                if (!pdfBoxInited) {
                    PDFBoxResourceLoader.init(context.applicationContext)
                    pdfBoxInited = true
                }
            }
        }
    }
}
