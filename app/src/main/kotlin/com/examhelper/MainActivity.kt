package com.examhelper

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.examhelper.core.Config
import com.examhelper.core.KnowledgeBaseLoader
import com.examhelper.core.LlmProfile
import com.examhelper.core.LogUtil
import com.examhelper.float.FloatingBallService
import com.examhelper.importer.BankParser
import com.examhelper.importer.ImportStore
import com.examhelper.importer.TextExtractor
import com.examhelper.importer.XlsxParser
import com.examhelper.search.LlmClient
import kotlinx.coroutines.runBlocking

class MainActivity : AppCompatActivity() {

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) importBank(uri)
    }

    /** 正在编辑的模型配置 id；为 null 表示「保存」为新增 */
    private var editingLlmId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btnOverlay).setOnClickListener {
            requestOverlayPermission()
        }
        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            openAccessibilitySettings()
        }
        findViewById<Button>(R.id.btnStartBall).setOnClickListener {
            startBall()
        }
        findViewById<Button>(R.id.btnSaveLlm).setOnClickListener {
            saveLlm()
        }
        findViewById<Button>(R.id.btnLlmDeepseek).setOnClickListener { fillTemplate("DeepSeek") }
        findViewById<Button>(R.id.btnLlmQwen).setOnClickListener { fillTemplate("通义千问") }
        findViewById<Button>(R.id.btnLlmKimi).setOnClickListener { fillTemplate("Kimi") }
        findViewById<Button>(R.id.btnLlmGlm).setOnClickListener { fillTemplate("智谱GLM") }
        findViewById<Button>(R.id.btnCopyLog).setOnClickListener {
            copyLog()
        }
        findViewById<Button>(R.id.btnClearLog).setOnClickListener {
            LogUtil.clear()
            toast("本地日志已清除")
        }
        findViewById<Button>(R.id.btnImport).setOnClickListener {
            importLauncher.launch(arrayOf("*/*"))
        }
        findViewById<Button>(R.id.btnClearImport).setOnClickListener {
            ImportStore.clear(this)
            toast("已清空所有导入的题库（内置知识库不受影响）")
            refreshBankList()
            updateStatus()
        }
        loadLlmIntoFields()
        updateStatus()

        LogUtil.i("MainActivity onCreate, 悬浮窗权限=${Settings.canDrawOverlays(this)}")
    }

    private fun requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        } else {
            toast("悬浮窗权限已开启")
        }
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun startBall() {
        LogUtil.i("startBall 点击, 悬浮窗权限=${Settings.canDrawOverlays(this)}")
        if (!Settings.canDrawOverlays(this)) {
            toast("请先开启悬浮窗权限")
            requestOverlayPermission()
            return
        }
        val i = Intent(this, FloatingBallService::class.java)
        try {
            startForegroundService(i)
            LogUtil.i("startBall 已调用 startForegroundService")
        } catch (t: Throwable) {
            LogUtil.e("startBall 启动服务失败", t)
            toast("启动悬浮球失败：${t.message}")
            return
        }
        toast("悬浮球已显示，长按关闭")
    }

    private fun copyLog() {
        val text = LogUtil.readLog()
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("examhelper_log", text))
        toast("日志已复制到剪贴板，请粘贴发送")
        LogUtil.i("日志已复制到剪贴板, 长度=${text.length}")
    }

    private fun importBank(uri: Uri) {
        toast("开始解析题库…")
        Thread {
            try {
                val name = bankName(uri)
                // Excel 表格是「按列」存题目/答案，走专用解析（通用文本提取会得到乱码）
                if (isXlsxFile(uri)) {
                    val items = XlsxParser.parseQa(this, uri)
                    if (items.isEmpty()) {
                        LogUtil.w("XlsxParser: 未解析出题目，导入中止")
                        runOnUiThread {
                            toast("导入失败：表格中未识别到题目（需包含「题目」和「答案」两列）")
                        }
                        return@Thread
                    }
                    val bank = ImportStore.add(this, name, "qa", items, emptyList())
                    runOnUiThread {
                        toast("导入成功「${bank.name}」：问答 ${bank.itemCount}")
                        refreshBankList()
                        updateStatus()
                    }
                    return@Thread
                }
                val text = TextExtractor.extract(this, uri)
                LogUtil.i("提取文本长度=${text.length}")
                val visionType = detectTypeByAi(uri, text)
                val res = BankParser.parse(text, name, visionType)
                val bank = if (res.type == "qa") {
                    ImportStore.add(this, name, "qa", res.qaItems, emptyList())
                } else {
                    ImportStore.add(this, name, "kb", emptyList(), res.documents)
                }
                runOnUiThread {
                    toast("导入成功「${bank.name}」：${typeLabel(bank.type)} ${bank.itemCount}")
                    refreshBankList()
                    updateStatus()
                }
            } catch (t: Throwable) {
                LogUtil.e("导入题库失败", t)
                runOnUiThread { toast("导入失败：${t.message}") }
            }
        }.start()
    }

    /** 判断文件是否为 .xlsx（Excel 2007+ 表格） */
    private fun isXlsxFile(uri: Uri): Boolean {
        val raw = runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else ""
            } ?: ""
        }.getOrDefault("")
        return raw.lowercase().endsWith(".xlsx")
    }

    /**
     * AI 辅助判断题库类型：仅当已配置联网 LLM 时尝试。
     *   - PDF：渲染前两页，走视觉识别；
     *   - docx / txt 等：取文本开头样本，走文本判断（比渲染图片更稳）。
     * 判断是「题目答案题库(qa)」还是「条文/教材/知识点(kb)」。
     * 任何失败都返回 null，回退到本地启发式判定，不影响导入。
     */
    private fun detectTypeByAi(uri: Uri, text: String): String? {
        val llm = Config.loadLlm(this) ?: return null
        val isPdf = runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) {
                    c.getString(idx)?.lowercase()?.endsWith(".pdf") == true
                } else false
            } ?: false
        }.getOrDefault(false)

        val prompt = "以下是某个题库/文档文件的开头部分。请判断它整体属于哪种类型：\n" +
            "A. 题目答案题库（选择题/判断题/填空题/问答题，通常带 A/B/C/D 选项或「答案：」标记）；\n" +
            "B. 条文/教材/知识点（规章制度、教材章节、条文，如「第X条」「第X章」或知识点讲解段落）。\n" +
            "只回答一个词：qa（表示题目答案题库）或 kb（表示条文/教材/知识点）。"

        val pages = ArrayList<Bitmap>()
        return try {
            val images: List<String>
            val finalPrompt: String
            if (isPdf) {
                pages.addAll(TextExtractor.renderPdfPages(this, uri, 2))
                if (pages.isEmpty()) return null
                images = pages.map { TextExtractor.bitmapToDataUrl(it) }
                finalPrompt = prompt
            } else {
                images = emptyList()
                finalPrompt = prompt + "\n\n文档开头内容（节选）：\n" + text.take(1200)
            }
            val answer = runBlocking {
                LlmClient.askVision(llm.baseUrl, llm.apiKey, llm.model, images, finalPrompt)
            }
            val t = answer?.trim()?.lowercase() ?: return null
            when {
                t.contains("kb") -> "kb"
                t.contains("qa") -> "qa"
                else -> null
            }
        } catch (e: Throwable) {
            LogUtil.w("AI 辅助判断类型失败，回退本地判定: ${e.message}")
            null
        } finally {
            pages.forEach { runCatching { if (!it.isRecycled) it.recycle() } }
        }
    }

    /** 从 uri 取文件名（去扩展名）作为题库名称 */
    private fun bankName(uri: Uri): String {
        val raw = runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else ""
            } ?: ""
        }.getOrDefault("")
        val base = raw.substringBeforeLast('.').trim()
        return base.ifBlank { "题库-${System.currentTimeMillis() % 100000}" }
    }

    private fun typeLabel(type: String) = if (type == "qa") "问答" else "条文"

    private fun saveLlm() {
        val name = findViewById<EditText>(R.id.etLlmName).text.toString().trim()
        val url = findViewById<EditText>(R.id.etLlmUrl).text.toString().trim()
        val key = findViewById<EditText>(R.id.etLlmKey).text.toString().trim()
        val model = findViewById<EditText>(R.id.etLlmModel).text.toString().trim()
        if (url.isEmpty() || key.isEmpty() || model.isEmpty()) {
            toast("API 地址 / API Key / 模型名 均不能为空")
            return
        }
        // 名称缺省时用模型名填充
        val finalName = name.ifBlank { model }
        val profile = LlmProfile(
            id = editingLlmId ?: "",
            name = finalName,
            baseUrl = url,
            apiKey = key,
            model = model
        )
        val saved = Config.saveProfile(this, profile)
        // 新增或编辑的配置自动设为当前使用，切换模型无需再手动选
        Config.setCurrentProfile(this, saved.id)
        editingLlmId = null
        toast("已保存「${saved.name}」（${saved.model}）并设为当前使用")
        refreshLlmProfiles()
        updateStatus()
    }

    /** 预设的 OpenAI 兼容服务商模板：只需用户填 API Key */
    private data class LlmTemplate(val name: String, val baseUrl: String, val model: String)

    private val LLM_TEMPLATES = listOf(
        LlmTemplate("DeepSeek", "https://api.deepseek.com", "deepseek-flash"),
        LlmTemplate("通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus"),
        LlmTemplate("Kimi", "https://api.moonshot.cn/v1", "kimi-k3.1-pro"),
        LlmTemplate("智谱GLM", "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash")
    )

    private fun fillTemplate(name: String) {
        val t = LLM_TEMPLATES.firstOrNull { it.name == name } ?: return
        findViewById<EditText>(R.id.etLlmName).setText(t.name)
        findViewById<EditText>(R.id.etLlmUrl).setText(t.baseUrl)
        findViewById<EditText>(R.id.etLlmModel).setText(t.model)
        editingLlmId = null
        toast("已填入「${t.name}」，请填写 API Key 后点「保存联网配置」")
    }

    private fun loadLlmIntoFields() {
        val cfg = Config.loadLlm(this) ?: return
        findViewById<EditText>(R.id.etLlmName).setText(cfg.name)
        findViewById<EditText>(R.id.etLlmUrl).setText(cfg.baseUrl)
        findViewById<EditText>(R.id.etLlmKey).setText(cfg.apiKey)
        findViewById<EditText>(R.id.etLlmModel).setText(cfg.model)
    }

    /** 编辑某份配置：回填表单并记录编辑 id */
    private fun editProfile(p: LlmProfile) {
        editingLlmId = p.id
        findViewById<EditText>(R.id.etLlmName).setText(p.name)
        findViewById<EditText>(R.id.etLlmUrl).setText(p.baseUrl)
        findViewById<EditText>(R.id.etLlmKey).setText(p.apiKey)
        findViewById<EditText>(R.id.etLlmModel).setText(p.model)
        toast("正在编辑「${p.name}」，改完点「保存联网配置」")
    }

    private fun startEditNewProfile() {
        editingLlmId = null
        findViewById<EditText>(R.id.etLlmName).setText("")
        findViewById<EditText>(R.id.etLlmUrl).setText("")
        findViewById<EditText>(R.id.etLlmKey).setText("")
        findViewById<EditText>(R.id.etLlmModel).setText("")
    }

    /** 渲染已保存的模型配置列表 */
    private fun refreshLlmProfiles() {
        val container = findViewById<LinearLayout>(R.id.llLlmProfiles) ?: return
        container.removeAllViews()
        val curId = Config.currentProfileId(this)
        val profiles = Config.listProfiles(this)
        if (profiles.isEmpty()) {
            val tv = TextView(this).apply {
                text = "（暂无保存的配置）"
                textSize = 13f
                setTextColor(getColor(R.color.text_secondary))
                setPadding(0, 4, 0, 4)
            }
            container.addView(tv)
        }
        for (p in profiles) {
            container.addView(profileRow(p, p.id == curId))
        }
    }

    private fun profileRow(p: LlmProfile, isCurrent: Boolean): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 4, 0, 4)
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val tv = TextView(this).apply {
            text = (if (isCurrent) "● " else "○ ") + "${p.name}（${p.model}）\n${p.baseUrl}"
            textSize = 13f
            setTextColor(if (isCurrent) getColor(R.color.primary) else getColor(R.color.text_primary))
            setPadding(0, 4, 8, 4)
            setOnClickListener {
                Config.setCurrentProfile(this@MainActivity, p.id)
                toast("已切换到「${p.name}」")
                refreshLlmProfiles()
                updateStatus()
            }
        }
        row.addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val edit = Button(this).apply {
            text = "编"
            setOnClickListener { editProfile(p) }
        }
        row.addView(edit, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val del = Button(this).apply {
            text = "删"
            setOnClickListener {
                Config.deleteProfile(this@MainActivity, p.id)
                if (editingLlmId == p.id) editingLlmId = null
                toast("已删除「${p.name}」")
                refreshLlmProfiles()
                updateStatus()
            }
        }
        row.addView(del, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        return row
    }

    private fun updateStatus() {
        val sb = StringBuilder("状态：")
        sb.append(if (Settings.canDrawOverlays(this)) "悬浮窗✓ " else "悬浮窗✗ ")
        sb.append(if (Config.loadLlm(this) != null) "联网✓" else "联网未配置")
        findViewById<TextView>(R.id.tvStatus).text = sb.toString()
        findViewById<TextView>(R.id.tvImportStatus).text = ImportStore.summary(this)
        refreshBankList()
        refreshLlmProfiles()

        // 当前使用的模型配置
        val cfg = Config.loadLlm(this)
        findViewById<TextView>(R.id.tvCurrentLlm).text =
            if (cfg != null) "当前使用：${cfg.name.ifBlank { cfg.model }}（${cfg.model}）"
            else "当前使用：未配置"
    }

    /** 动态渲染题库列表：内置知识库（可开关）+ 所有导入题库（可开关/删除） */
    private fun refreshBankList() {
        val container = findViewById<LinearLayout>(R.id.llBankList) ?: return
        container.removeAllViews()
        container.addView(builtinRow())
        for (bank in ImportStore.list(this)) {
            container.addView(bankRow(bank))
        }
    }

    private fun builtinRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 8)
        }
        val cb = CheckBox(this).apply {
            val count = KnowledgeBaseLoader.articleCount(
                KnowledgeBaseLoader.get(this@MainActivity)
            )
            text = "内置 · 本地知识库（条文 $count 条）"
            isChecked = Config.isBuiltinEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                Config.setBuiltinEnabled(this@MainActivity, checked)
                toast(if (checked) "已启用内置知识库" else "已停用内置知识库")
            }
        }
        row.addView(cb, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun bankRow(bank: ImportStore.ImportedBank): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 4, 0, 4)
        }
        val cb = CheckBox(this).apply {
            text = "${bank.name}（${typeLabel(bank.type)} ${bank.itemCount}）"
            isChecked = bank.enabled
            setOnCheckedChangeListener { _, checked ->
                ImportStore.setEnabled(this@MainActivity, bank.id, checked)
                updateStatus()
            }
        }
        row.addView(cb, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val del = Button(this).apply {
            text = "删"
            setOnClickListener {
                ImportStore.remove(this@MainActivity, bank.id)
                toast("已删除「${bank.name}」")
                updateStatus()
            }
        }
        row.addView(
            del,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        return row
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
