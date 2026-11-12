package com.examhelper.capture

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.examhelper.core.LogUtil
import com.examhelper.ocr.OcrEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 前台服务：常驻屏幕截图会话（MediaProjection）。
 *
 * 设计要点（Android 14 / API 34+ 硬性要求）：
 *   1. 授权 token 一次性、每个 MediaProjection 实例只能 createVirtualDisplay 一次；
 *   2. 但只要会话建立后【不释放】，就能持续反复取帧，无需重复授权。
 *
 * 因此本服务采用「授权一次 → 常驻会话 → 复用取帧」模式：
 *   - 首次启动：携带授权结果（EXTRA_RESULT_CODE / EXTRA_RESULT_DATA），
 *     建立 MediaProjection + VirtualDisplay + ImageReader，之后保持常驻；
 *   - 后续启动：不带授权参数，直接对常驻 ImageReader 取最新一帧；
 *   - 会话被系统终止（锁屏/用户从系统 UI 停止投屏/进程被杀）时，
 *     Callback.onStop 会清理会话并结束服务，下次识别需重新授权一次。
 *
 * 关键顺序：
 *   1. startForegroundService 后必须尽快 startForeground(mediaProjection)；
 *   2. getMediaProjection() 前必须已进入 mediaProjection 前台服务；
 *   3. createVirtualDisplay() 前必须先 registerCallback()。
 */
class ScreenCaptureService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val ACTION_CAPTURE_RESULT = "com.examhelper.action.CAPTURE_RESULT"
        const val EXTRA_TEXT = "extra_text"
        const val ACTION_CAPTURE_FAILED = "com.examhelper.action.CAPTURE_FAILED"
        const val EXTRA_REASON = "extra_reason"

        /**
         * 识别请求序号：由 FloatingBallService 每次点悬浮球时自增下发，
         * 结果/失败广播原样带回，接收方据此丢弃过期结果，避免上一次识别的迟到
         * 结果覆盖本次识别（也可以防止超时/取消后仍被写入面板）。
         */
        const val EXTRA_ATTEMPT_ID = "extra_attempt_id"

        // 常驻会话状态（跨 onStartCommand 复用；服务存活期间一直保留）
        @Volatile
        var projection: MediaProjection? = null
            private set
        @Volatile
        var virtualDisplay: VirtualDisplay? = null
            private set
        @Volatile
        var imageReader: ImageReader? = null
            private set
        var projectionCallback: MediaProjection.Callback? = null
            private set
        @Volatile
        var width = 0
            private set
        @Volatile
        var height = 0
            private set

        /** 是否已有可复用的常驻截图会话 */
        fun isActive(): Boolean = projection != null && virtualDisplay != null

        /** 释放常驻会话（幂等）。调用后下次识别需重新授权。 */
        fun stopSession() {
            runCatching { virtualDisplay?.release() }
            runCatching { projectionCallback?.let { projection?.unregisterCallback(it) } }
            runCatching { projection?.stop() }
            runCatching { imageReader?.close() }
            virtualDisplay = null
            projectionCallback = null
            projection = null
            imageReader = null
            width = 0
            height = 0
            LogUtil.i("截图会话已释放")
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())

    /** 本次取帧对应的识别请求序号（来自 Intent，随广播回传） */
    private var currentAttemptId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        currentAttemptId = intent?.getIntExtra(EXTRA_ATTEMPT_ID, 0) ?: 0
        LogUtil.i("ScreenCaptureService.onStartCommand, active=${isActive()}, attempt=$currentAttemptId")
        // 先进入前台（mediaProjection 类型），再决定走复用还是初始化。
        if (!startForegroundCompat()) {
            LogUtil.e("ScreenCaptureService 进入前台失败，终止")
            stopSelf()
            return START_NOT_STICKY
        }

        // 已有常驻会话 → 直接取帧，无需授权
        if (isActive()) {
            captureNow()
            return START_STICKY
        }

        // 首次授权 → 初始化会话
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        val data = if (Build.VERSION.SDK_INT >= 33)
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        else
            @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_RESULT_DATA)

        // 注意：Activity.RESULT_OK == -1，切勿写成 resultCode == -1 判失败。
        if (resultCode != Activity.RESULT_OK || data == null) {
            LogUtil.w("ScreenCaptureService 未拿到授权且无活跃会话 resultCode=$resultCode data=${data != null}")
            stopSelf()
            return START_NOT_STICKY
        }

        initSession(resultCode, data)
        return START_STICKY
    }

    /** 用授权结果建立常驻会话，随后立即取一帧 */
    private fun initSession(resultCode: Int, data: Intent) {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(resultCode, data)
        val proj = projection ?: run {
            LogUtil.w("getMediaProjection 返回 null")
            stopSelf(); return
        }

        // Android 14+ 强制：createVirtualDisplay 前必须先注册回调。
        projectionCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                LogUtil.w("MediaProjection 会话被系统终止（锁屏/用户停止投屏/进程回收）")
                stopSession()
                stopSelf()
            }
        }
        proj.registerCallback(projectionCallback!!, handler)

        val metrics = resources.displayMetrics
        width = metrics.widthPixels
        height = metrics.heightPixels
        val density = metrics.densityDpi

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        virtualDisplay = proj.createVirtualDisplay(
            "examhelper-capture", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, handler
        )
        LogUtil.i("常驻截图会话已建立 ${width}x${height}，之后复用不再授权")
        captureNow()
    }

    /** 从常驻 ImageReader 取最新一帧 → OCR → 广播结果（不释放会话） */
    private fun captureNow() {
        val attempt = currentAttemptId
        val reader = imageReader
        if (reader == null) {
            LogUtil.w("无可用截图会话")
            broadcastFailure("no_session", attempt)
            stopSelf()
            return
        }
        tryAcquire(reader, attemptsLeft = 2, attempt = attempt)
    }

    /**
     * 尝试取帧；偶发空帧会重试一次。连续为空通常意味着屏幕捕获被抢占
     * （例如用户正在进行系统录屏 / 其他应用投屏），此时主动上报冲突并释放会话。
     */
    private fun tryAcquire(reader: ImageReader, attemptsLeft: Int, attempt: Int) {
        handler.postDelayed({
            runCatching {
                val image = reader.acquireLatestImage()
                if (image != null) {
                    processImage(image, attempt)
                } else if (attemptsLeft > 1) {
                    LogUtil.w("截图取帧为空，重试…")
                    tryAcquire(reader, attemptsLeft - 1, attempt)
                } else {
                    LogUtil.w("连续取帧为空，屏幕捕获可能被录屏/其他应用占用")
                    broadcastFailure("capture_conflict", attempt)
                    stopSession()
                    stopSelf()
                }
            }.onFailure {
                LogUtil.e("ScreenCaptureService 截图失败", it)
                broadcastFailure("capture_error", attempt)
                stopSession()
                stopSelf()
            }
        }, 300)
    }

    /**
     * 解码一帧并交给 OCR。
     *
     * 注意（D1：位图/Image 泄漏）：
     *  1. [Image] 必须在 **finally** 里关闭——旧实现只在成功路径 close，解码抛异常时
     *     ImageReader 的缓冲区不归还，几次之后取帧就永远为空；
     *  2. 解码（copyPixelsFromBuffer）非常吃 CPU，不能放在主线程（旧实现跑在 handler 主线程）；
     *  3. 位图在 OCR 结束后必须 recycle——一张 1080P ARGB_8888 约 8MB，旧实现每识别一次
     *     泄漏一张，连续用几十次就 OOM。
     */
    private fun processImage(image: Image, attempt: Int) {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) {
            runCatching { image.close() }
            broadcastFailure("bad_geometry", attempt)
            return
        }

        val raw: Bitmap = try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * w
            val bmp = Bitmap.createBitmap(w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888)
            try {
                bmp.copyPixelsFromBuffer(buffer)
                bmp
            } catch (t: Throwable) {
                if (!bmp.isRecycled) bmp.recycle()
                throw t
            }
        } catch (t: Throwable) {
            LogUtil.e("截图位图解码失败", t)
            broadcastFailure("decode_error", attempt)
            return
        } finally {
            // 无论解码成功与否都归还 Image，否则后续取帧会一直为空
            runCatching { image.close() }
        }

        scope.launch(Dispatchers.Default) {
            var cropped: Bitmap? = null
            try {
                cropped = if (raw.width == w && raw.height == h) raw
                else Bitmap.createBitmap(raw, 0, 0, w, h)
                val text = OcrEngine.recognize(cropped)
                broadcast(text, attempt)
            } catch (t: Throwable) {
                LogUtil.e("OCR 识别失败", t)
                broadcastFailure("ocr_error", attempt)
            } finally {
                // 所有位图（含裁剪出的中间位图）用完立即回收
                if (cropped != null && cropped !== raw && !cropped.isRecycled) cropped.recycle()
                if (!raw.isRecycled) raw.recycle()
            }
        }
    }

    private fun broadcast(text: String, attempt: Int) {
        val intent = Intent(ACTION_CAPTURE_RESULT)
        intent.setPackage(packageName)
        intent.putExtra(EXTRA_TEXT, text)
        intent.putExtra(EXTRA_ATTEMPT_ID, attempt)
        sendBroadcast(intent)
    }

    private fun broadcastFailure(reason: String, attempt: Int) {
        val intent = Intent(ACTION_CAPTURE_FAILED)
        intent.setPackage(packageName)
        intent.putExtra(EXTRA_REASON, reason)
        intent.putExtra(EXTRA_ATTEMPT_ID, attempt)
        sendBroadcast(intent)
    }

    override fun onDestroy() {
        LogUtil.i("ScreenCaptureService.onDestroy")
        // 先停定时任务与协程，再释放会话并关掉 OCR 引擎（D6：旧实现两者都没清）
        handler.removeCallbacksAndMessages(null)
        runCatching { scope.cancel() }
        stopSession()
        OcrEngine.close()
        super.onDestroy()
    }

    /** 以 mediaProjection 类型进入前台；返回是否成功 */
    private fun startForegroundCompat(): Boolean {
        val channelId = "capture"
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(channelId, "屏幕识别", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification: Notification = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, channelId)
                .setContentTitle("屏幕识别会话运行中")
                .setContentText("已授权，识别题目时无需重复授权；长按悬浮球可结束")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("屏幕识别会话运行中")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .build()
        }
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(1001, notification)
            }
            LogUtil.i("ScreenCaptureService.startForeground 成功")
            true
        } catch (t: Throwable) {
            LogUtil.e("ScreenCaptureService.startForeground 失败", t)
            false
        }
    }
}
