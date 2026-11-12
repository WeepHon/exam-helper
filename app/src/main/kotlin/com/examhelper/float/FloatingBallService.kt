package com.examhelper.float

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.examhelper.R
import com.examhelper.a11y.AnswerAccessibilityService
import com.examhelper.capture.CapturePermissionActivity
import com.examhelper.capture.ScreenCaptureService
import com.examhelper.core.AnswerEngine
import com.examhelper.core.Config
import com.examhelper.core.LogUtil
import com.examhelper.core.QaItem
import com.examhelper.hook.ViewTextHook
import com.examhelper.search.LlmClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 悬浮球服务：屏幕上的可拖动小球，点击后识别当前屏幕题目并弹出答案面板。
 */
class FloatingBallService : Service() {

    private lateinit var windowManager: WindowManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())

    private var ballView: View? = null
    private var panelView: View? = null
    private var ballParams: WindowManager.LayoutParams? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var panelVisible = false
    private var busy = false
    private var awaitingText = false

    /**
     * 识别请求序号（D2）。每次点悬浮球自增，随「读取文本/截图」请求下发，
     * 回传的结果、失败、取消事件都必须带着同一个序号才被接受，
     * 否则上一次识别的迟到结果会覆盖本次，或在超时/取消后把面板改回旧答案。
     */
    private var attemptId = 0

    /** 超时兜底任务当前对应的识别序号 */
    private var timeoutAttempt = 0

    // 追问上下文：记住最近一次识别的题目与答案，支持多轮追问 / 纠正
    private var lastRawText = ""
    private var lastQuestion = ""
    private val followUpHistory = mutableListOf<Pair<String, String>>() // 交替 (assistant, user)
    private var busyFollowUp = false
    private var panelOriginY = 40

    private val captureReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ScreenCaptureService.ACTION_CAPTURE_RESULT) return
            val attempt = intent.getIntExtra(ScreenCaptureService.EXTRA_ATTEMPT_ID, -1)
            if (!isCurrentAttempt(attempt)) {
                LogUtil.w("忽略过期的截图结果 attempt=$attempt current=$attemptId")
                return
            }
            val text = intent.getStringExtra(ScreenCaptureService.EXTRA_TEXT) ?: ""
            onTextReady(text, attempt)
        }
    }

    // 接收目标 App 内 Hook 回传的控件文本（精准模式）
    private val hookReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ViewTextHook.ACTION_TEXT_RESULT) return
            val attempt = intent.getIntExtra(ViewTextHook.EXTRA_ATTEMPT_ID, -1)
            if (!isCurrentAttempt(attempt)) {
                LogUtil.w("忽略过期的 Hook 文本 attempt=$attempt current=$attemptId")
                return
            }
            val text = intent.getStringExtra(ViewTextHook.EXTRA_TEXT) ?: ""
            if (text.isNotBlank()) onTextReady(text, attempt)
        }
    }

    // 接收「用户取消屏幕识别授权」事件，复位 busy 状态，避免悬浮球卡死
    private val cancelReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != CapturePermissionActivity.ACTION_CAPTURE_CANCELED) return
            LogUtil.i("收到屏幕识别取消事件，复位状态")
            failAttempt(intent.getIntExtra(ScreenCaptureService.EXTRA_ATTEMPT_ID, -1), "已取消屏幕识别")
        }
    }

    // 接收「截图失败」事件（如录屏冲突导致取不到帧），给出明确提示
    private val captureFailedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ScreenCaptureService.ACTION_CAPTURE_FAILED) return
            val reason = intent.getStringExtra(ScreenCaptureService.EXTRA_REASON) ?: ""
            LogUtil.w("屏幕捕获失败: $reason")
            val msg = if (reason == "capture_conflict")
                "检测到屏幕捕获被占用（可能正在录屏），请停止录屏后重新识别"
            else
                "截图失败，请重试"
            failAttempt(intent.getIntExtra(ScreenCaptureService.EXTRA_ATTEMPT_ID, -1), msg)
        }
    }

    // 可取消的超时兜底任务，避免多次点击堆积多个定时器导致误触发
    private val captureTimeoutRunnable = Runnable {
        if (awaitingText && timeoutAttempt == attemptId) {
            LogUtil.w("屏幕识别超时，复位状态 attempt=$timeoutAttempt")
            busy = false
            awaitingText = false
            showPanelWithStatus("识别超时，请重试", question = "")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        LogUtil.i("FloatingBallService.onStartCommand")
        startForegroundCompat()
        ensureBall()
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        LogUtil.i("FloatingBallService.onCreate, sdk=${Build.VERSION.SDK_INT}")
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val captureFilter = IntentFilter(ScreenCaptureService.ACTION_CAPTURE_RESULT)
        val hookFilter = IntentFilter(ViewTextHook.ACTION_TEXT_RESULT)
        val cancelFilter = IntentFilter(CapturePermissionActivity.ACTION_CAPTURE_CANCELED)
        val failedFilter = IntentFilter(ScreenCaptureService.ACTION_CAPTURE_FAILED)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(captureReceiver, captureFilter, Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(hookReceiver, hookFilter, Context.RECEIVER_EXPORTED)
            registerReceiver(cancelReceiver, cancelFilter, Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(captureFailedReceiver, failedFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(captureReceiver, captureFilter)
            registerReceiver(hookReceiver, hookFilter)
            registerReceiver(cancelReceiver, cancelFilter)
            registerReceiver(captureFailedReceiver, failedFilter)
        }
        LogUtil.i("FloatingBallService.onCreate 广播接收器已注册")
    }

    // ---------- 悬浮球 ----------

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    private fun ensureBall() {
        if (ballView != null) return
        val tv = TextView(this)
        tv.text = "题"
        tv.setTextColor(android.graphics.Color.WHITE)
        tv.textSize = 16f
        tv.gravity = Gravity.CENTER
        tv.setBackgroundResource(R.drawable.bg_ball)

        val size = (56 * resources.displayMetrics.density).toInt()
        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 40
        params.y = 300

        tv.setOnTouchListener(BallTouchListener(params))
        tv.setOnClickListener {
            if (!busy) recognize()
        }

        runCatching { windowManager.addView(tv, params) }.onFailure {
            LogUtil.e("悬浮球 addView 失败（可能无悬浮窗权限）", it)
            Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show()
            stopSelf()
            return
        }
        ballView = tv
        ballParams = params
        LogUtil.i("悬浮球已显示: size=$size x=${params.x} y=${params.y}")
    }

    private inner class BallTouchListener(
        private val params: WindowManager.LayoutParams
    ) : View.OnTouchListener {
        private var initialX = 0
        private var initialY = 0
        private var initialTouchX = 0f
        private var initialTouchY = 0f
        private var moved = false
        private var downTime = 0L
        private val longPress = Runnable { if (!moved) stopSelf() }

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    moved = false
                    downTime = System.currentTimeMillis()
                    handler.postDelayed(longPress, 600)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8) {
                        moved = true
                        handler.removeCallbacks(longPress)
                    }
                    params.x = initialX + dx
                    params.y = initialY + dy
                    runCatching { windowManager.updateViewLayout(v, params) }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPress)
                    if (!moved) {
                        v.performClick()
                    }
                    return true
                }
            }
            return false
        }
    }

    // ---------- 识别流程 ----------

    private fun recognize() {
        if (busy) return
        val attempt = ++attemptId
        busy = true
        awaitingText = true
        // 关键：识别期间必须隐藏答案面板。面板（含题目、答案、追问框）如果显示在屏幕上，
        // 会被 OCR/无障碍当成屏幕内容一起读入，污染识别原文，导致题干抽取错误。
        hidePanel()
        toast("正在识别题目…")
        try {
            // 1) 优先无障碍直接读文本（最准）
            val a11yText = AnswerAccessibilityService.instance?.collectCurrentText()
            if (!a11yText.isNullOrBlank()) {
                onTextReady(a11yText, attempt)
                return
            }
            // 2) 尝试精准模式：请求目标 App 内 Hook 回传控件文本
            val fg = AnswerAccessibilityService.currentPackage
            if (!fg.isNullOrBlank() && fg != packageName) {
                toast("正在读取应用文本…")
                scope.launch {
                    val req = Intent(ViewTextHook.ACTION_READ_TEXT)
                    req.setPackage(fg)
                    req.putExtra(ViewTextHook.EXTRA_PKG, fg)
                    req.putExtra(ViewTextHook.EXTRA_ATTEMPT_ID, attempt)
                    // 明确告诉 Hook 把结果发回哪个包（联网版/仅本地版包名不同，写死会串包）
                    req.putExtra(ViewTextHook.EXTRA_REPLY_PKG, packageName)
                    runCatching { sendBroadcast(req) }
                        .onFailure { LogUtil.e("发送读取文本请求失败", it) }
                    delay(1500) // 等待 Hook 回传；超时则回退 OCR
                    if (awaitingText && attempt == attemptId) {
                        launchCapturePermission(attempt)
                    }
                }
                return
            }
            // 3) 回退到屏幕截图 OCR
            launchCapturePermission(attempt)
        } catch (t: Throwable) {
            LogUtil.e("启动识别失败", t)
            failAttempt(attempt, "识别启动失败，请重试")
        }
    }

    /** 启动屏幕识别：已有常驻会话则直接复用截图，否则请求授权 */
    private fun launchCapturePermission(attempt: Int) {
        // 已有常驻截图会话 → 直接触发截图，无需再次授权
        if (ScreenCaptureService.isActive()) {
            toast("正在截图识别…")
            runCatching {
                startForegroundService(
                    Intent(this, ScreenCaptureService::class.java)
                        .putExtra(ScreenCaptureService.EXTRA_ATTEMPT_ID, attempt)
                )
            }.onFailure {
                LogUtil.e("启动截图服务失败", it)
                failAttempt(attempt, "无法启动截图识别")
                return
            }
            scheduleCaptureTimeout(attempt)
            return
        }
        // 首次：走系统授权
        toast("正在请求屏幕识别授权…")
        runCatching {
            CapturePermissionActivity.start(this, attempt)
        }.onFailure {
            LogUtil.e("启动授权界面失败", it)
            failAttempt(attempt, "无法启动屏幕识别")
            return
        }
        scheduleCaptureTimeout(attempt)
    }

    /** 超时兜底：先取消上一次定时，避免多次点击堆积多个定时器导致误触发 */
    private fun scheduleCaptureTimeout(attempt: Int) {
        timeoutAttempt = attempt
        handler.removeCallbacks(captureTimeoutRunnable)
        handler.postDelayed(captureTimeoutRunnable, 30_000L)
    }

    /** 本次事件是否属于当前进行中的识别请求 */
    private fun isCurrentAttempt(attempt: Int): Boolean =
        attempt == attemptId && awaitingText

    /** 统一失败出口：只有当前请求才能复位状态、改面板，避免旧事件把新识别打断 */
    private fun failAttempt(attempt: Int, msg: String) {
        if (attempt != attemptId) {
            LogUtil.w("忽略过期的失败事件 attempt=$attempt current=$attemptId")
            return
        }
        busy = false
        awaitingText = false
        handler.removeCallbacks(captureTimeoutRunnable)
        runCatching { showPanelWithStatus(msg, question = "") }
    }

    private fun onTextReady(rawText: String, attempt: Int) {
        if (attempt != attemptId || !awaitingText) {
            LogUtil.w("忽略过期的识别结果 attempt=$attempt current=$attemptId")
            return
        }
        awaitingText = false
        handler.removeCallbacks(captureTimeoutRunnable)
        scope.launch {
            try {
                val cfg = Config.loadLlm(this@FloatingBallService)
                lastRawText = rawText
                showPanelWithStatus("正在生成答案…", question = "")
                val result = withContext(Dispatchers.Default) {
                    AnswerEngine.answer(this@FloatingBallService, rawText, cfg)
                }
                if (attempt == attemptId) render(result)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // 旧实现没有任何兜底：这里一抛异常，busy 永远为 true，悬浮球从此点不动
                LogUtil.e("生成答案失败", t)
                if (attempt == attemptId) {
                    runCatching {
                        showPanelWithStatus("生成答案失败（${t.javaClass.simpleName}），请重试", question = "")
                    }
                }
            } finally {
                if (attempt == attemptId) busy = false
            }
        }
    }

    // ---------- 答案面板 ----------

    private fun render(result: com.examhelper.core.AnswerResult) {
        val qText = findViewInPanel<TextView>(R.id.tvQuestion)
        val aText = findViewInPanel<TextView>(R.id.tvAnswer)
        val title = findViewInPanel<TextView>(R.id.tvTitle)
        qText?.text = "题目：${result.question}"
        title?.text = "答案（${result.source}）"

        val sb = StringBuilder()
        // 导入题库高置信命中优先展示（最准）。
        // 注意：答案不能只给字母——考试时选项顺序会随机变化，字母对应不上；
        // 必须把字母解析成「具体选项内容」，用户照着内容选才不会被选项顺序坑。
        if (result.qaMatches.isNotEmpty()) {
            result.qaMatches.forEachIndexed { i, qa ->
                if (i > 0) sb.append('\n')
                // 先展示命中的题目，再展示答案，方便用户确认这道题就是屏幕上的题
                val q = qa.question.trim()
                sb.append("【命中题目${i + 1}】")
                if (q.isNotEmpty()) {
                    sb.append(q).append('\n')
                } else {
                    sb.append('\n')
                }
                sb.append(resolveQaAnswer(qa))
                if (qa.options.isNotBlank()) {
                    sb.append('\n').append(qa.options)
                }
                if (i == 0) {
                    result.qaCheck?.let { sb.append('\n').append(it.describe()) }
                }
            }
        }
        // 双路 LLM 答案
        if (!result.kbAnswer.isNullOrBlank()) {
            if (sb.isNotEmpty()) sb.append("\n\n")
            sb.append("【依据本地题库】\n").append(result.kbAnswer)
        }
        if (!result.webAnswer.isNullOrBlank()) {
            if (sb.isNotEmpty()) sb.append("\n\n")
            sb.append("【答案】\n").append(result.webAnswer)
        }
        // 本地命中条款作为依据参考
        if (result.localMatches.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.append("\n\n")
            sb.append("【本地命中条款】\n").append(AnswerEngine.formatLocal(result.localMatches))
        }
        aText?.text = sb.toString()
        // 记录追问上下文：题目 + 本轮的完整答案文本
        lastQuestion = result.question
        followUpHistory.clear()
        val answerText = sb.toString()
        if (answerText.isNotBlank()) {
            followUpHistory.add("assistant" to answerText)
        }
        showPanel()
    }

    /**
     * 把题库答案转成「可直接照抄」的展示文本。
     * 选择题答案存的是字母（如 "B"、"ACD"），但考试时选项顺序会随机变化，字母对应不上；
     * 因此从选项文本里把字母解析成具体内容，如 "B：不进行任何形式的赌博"。
     * 判断题（"对/错"）、填空题/简答题（具体文本）直接原样返回。
     */
    private fun resolveQaAnswer(qa: QaItem): String {
        val ans = qa.answer.trim()
        if (ans.isEmpty()) return "（题库未提供答案）"
        // 纯字母（单选/多选）：解析出对应选项内容
        if (ans.matches(Regex("[A-Ha-h]+"))) {
            val opts = parseOptions(qa.options)
            if (opts.isNotEmpty()) {
                val parts = ans.uppercase().mapNotNull { ch ->
                    opts[ch.toString()]?.takeIf { it.isNotBlank() }
                }
                if (parts.isNotEmpty()) {
                    return "答案 ${ans.uppercase()}（" + parts.joinToString("；") + "）"
                }
            }
            return "答案 ${ans.uppercase()}"
        }
        return "答案 $ans"
    }

    /** 把 "A.xx  B.xx  C.xx" 解析成 {A:xx, B:xx, C:xx} */
    private fun parseOptions(options: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        val regex = Regex("([A-Ha-h])\\s*[.、．:：)）]\\s*")
        val matches = regex.findAll(options).toList()
        for (i in matches.indices) {
            val m = matches[i]
            val key = m.groupValues[1].uppercase()
            val start = m.range.last + 1
            val end = if (i + 1 < matches.size) matches[i + 1].range.first else options.length
            val content = options.substring(start, end).trim()
            if (content.isNotEmpty()) map[key] = content
        }
        return map
    }

    private fun showPanelWithStatus(status: String, question: String) {
        ensurePanel()
        findViewInPanel<TextView>(R.id.tvAnswer)?.text = status
        findViewInPanel<TextView>(R.id.tvQuestion)?.text =
            if (question.isNotEmpty()) "题目：$question" else ""
        showPanel()
    }

    @SuppressLint("InflateParams")
    private fun ensurePanel() {
        if (panelView != null) return
        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.view_answer_panel, null)
        val width = (resources.displayMetrics.widthPixels * 0.92).toInt()
        val params = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        params.y = 40
        panelOriginY = params.y

        view.findViewById<View>(R.id.btnClose).setOnClickListener { hidePanel() }
        view.findViewById<View>(R.id.btnRefresh).setOnClickListener {
            if (!busy) recognize()
        }

        // 追问输入框：悬浮窗默认 NOT_FOCUSABLE，无法弹键盘，点击时临时切换为可输入
        val etFollowUp = view.findViewById<EditText>(R.id.etFollowUp)
        etFollowUp.setOnClickListener {
            enablePanelInput(true)
            handler.post {
                etFollowUp.requestFocus()
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(etFollowUp, InputMethodManager.SHOW_IMPLICIT)
            }
        }
        etFollowUp.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) enablePanelInput(false)
        }
        view.findViewById<Button>(R.id.btnSend).setOnClickListener { sendFollowUp() }

        runCatching { windowManager.addView(view, params) }.onFailure {
            return
        }
        panelView = view
        panelParams = params
        hidePanel()
    }

    private fun showPanel() {
        val v = panelView ?: return
        if (!panelVisible) {
            v.visibility = View.VISIBLE
            panelVisible = true
        }
    }

    private fun hidePanel() {
        val v = panelView ?: return
        // 收起面板时复位输入态，避免残留键盘与上移的面板位置
        enablePanelInput(false)
        v.visibility = View.GONE
        panelVisible = false
    }

    /**
     * 切换答案面板是否可接收输入焦点。
     * 悬浮窗默认 FLAG_NOT_FOCUSABLE（不抢目标 App 焦点），输入追问时需要临时去掉该 flag
     * 才能让 EditText 拿到焦点弹出软键盘；同时把面板上移，避免被键盘遮挡。
     */
    private fun enablePanelInput(enable: Boolean) {
        val v = panelView ?: return
        val params = panelParams ?: return
        val targetY: Int
        if (enable) {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            // 键盘约占屏幕下 40%，把面板上移到屏幕约 30% 高度处，让输入框露出来
            targetY = (resources.displayMetrics.heightPixels * 0.30).toInt()
        } else {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            targetY = panelOriginY
        }
        if (params.y != targetY) {
            params.y = targetY
        }
        runCatching { windowManager.updateViewLayout(v, params) }
    }

    /** 发送追问 / 纠正，基于当前题目的上下文进行多轮对话 */
    private fun sendFollowUp() {
        if (busyFollowUp) return
        val et = findViewInPanel<EditText>(R.id.etFollowUp) ?: return
        val note = et.text.toString().trim()
        if (note.isEmpty()) return
        val cfg = Config.loadLlm(this)
        if (cfg == null) {
            toast("尚未配置联网模型，无法追问（请到 App 主界面配置）")
            return
        }

        busyFollowUp = true
        val btn = findViewInPanel<Button>(R.id.btnSend)
        btn?.isEnabled = false
        btn?.text = "思考中…"

        scope.launch {
            // 注意（D2）：整个过程必须有 finally 复位按钮与 busyFollowUp，
            // 旧实现一旦网络层抛异常，按钮永久停在「思考中…」，追问从此不可用。
            try {
                val answer = withContext(Dispatchers.Default) {
                    LlmClient.askFollowUp(
                        baseUrl = cfg.baseUrl,
                        apiKey = cfg.apiKey,
                        model = cfg.model,
                        question = lastQuestion,
                        fullText = lastRawText,
                        history = followUpHistory.toList(),
                        userNote = note
                    )
                }
                followUpHistory.add("user" to note)
                val aText = findViewInPanel<TextView>(R.id.tvAnswer)
                val cur = aText?.text?.toString() ?: ""
                if (!answer.isNullOrBlank()) {
                    followUpHistory.add("assistant" to answer)
                    aText?.text = if (cur.isBlank()) answer else "$cur\n\n【追问】\n$answer"
                } else {
                    aText?.text =
                        if (cur.isBlank()) "（追问失败，请重试）" else "$cur\n\n【追问】（失败，请重试）"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                LogUtil.e("追问失败", t)
                runCatching {
                    val aText = findViewInPanel<TextView>(R.id.tvAnswer)
                    val cur = aText?.text?.toString() ?: ""
                    aText?.text = if (cur.isBlank()) "（追问失败，请重试）"
                    else "$cur\n\n【追问】（失败，请重试）"
                }
            } finally {
                busyFollowUp = false
                btn?.isEnabled = true
                btn?.text = "发送"
                runCatching {
                    // 滚动到答案底部
                    findViewInPanel<ScrollView>(R.id.scrollAnswer)?.post {
                        findViewInPanel<ScrollView>(R.id.scrollAnswer)?.fullScroll(ScrollView.FOCUS_DOWN)
                    }
                    et.setText("")
                    // 收起键盘，恢复面板为不抢焦点状态
                    val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.hideSoftInputFromWindow(et.windowToken, 0)
                    enablePanelInput(false)
                }
            }
        }
    }

    private fun <T : View> findViewInPanel(id: Int): T? = panelView?.findViewById(id)

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        LogUtil.i("FloatingBallService.onDestroy")
        // 先停协程与挂起的定时任务（长按结束悬浮球、超时兜底、Hook 等待）
        handler.removeCallbacksAndMessages(null)
        runCatching { scope.cancel() }
        runCatching { unregisterReceiver(captureReceiver) }
        runCatching { unregisterReceiver(hookReceiver) }
        runCatching { unregisterReceiver(cancelReceiver) }
        runCatching { unregisterReceiver(captureFailedReceiver) }
        runCatching { ballView?.let { windowManager.removeView(it) } }
        runCatching { panelView?.let { windowManager.removeView(it) } }
        // 悬浮球关闭时，同步结束常驻截图会话（释放屏幕共享与前台通知）
        runCatching { stopService(Intent(this, ScreenCaptureService::class.java)) }
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val channelId = "ball"
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(channelId, "悬浮球", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification: Notification = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, channelId)
                .setContentTitle("题库助手运行中")
                .setContentText("点击悬浮球识别题目")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("题库助手运行中")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .build()
        }
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(1002, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(1002, notification)
            }
            LogUtil.i("FloatingBallService.startForeground 成功")
        } catch (t: Throwable) {
            LogUtil.e("FloatingBallService.startForeground 失败", t)
        }
    }
}
