package com.examhelper.ocr

import android.graphics.Bitmap
import com.examhelper.core.LogUtil
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * 本地中文 OCR，基于 ML Kit 文字识别 v2（模型离线）。
 *
 * 生命周期：识别器是重对象（内含 native 模型），**可以且应当复用**，
 * 但服务结束时必须 [close] 释放；close 之后再调用 [recognize] 会自动重建，
 * 因此不会出现「关掉一次以后永远识别不了」的情况。
 */
object OcrEngine {

    private val lock = Any()
    private var recognizer: TextRecognizer? = null

    private fun client(): TextRecognizer = synchronized(lock) {
        recognizer ?: TextRecognition
            .getClient(ChineseTextRecognizerOptions.Builder().build())
            .also { recognizer = it }
    }

    /**
     * 识别整张截图，返回全部文字（按行拼接）。
     * @param bitmap 调用方负责回收；本方法不会持有它到返回之后
     */
    suspend fun recognize(bitmap: Bitmap): String {
        if (bitmap.isRecycled) {
            LogUtil.w("OCR 输入位图已回收，跳过")
            return ""
        }
        return try {
            suspendCoroutine { cont ->
                // ML Kit 的成功/失败/取消回调互斥，但为防极端情况双 resume 崩溃，这里加一次性开关
                val resumed = AtomicBoolean(false)
                fun finish(text: String) {
                    if (resumed.compareAndSet(false, true)) cont.resume(text)
                }
                val image = InputImage.fromBitmap(bitmap, 0)
                client().process(image)
                    .addOnSuccessListener { finish(it.text) }
                    .addOnFailureListener {
                        LogUtil.w("OCR 识别失败: ${it.javaClass.simpleName}")
                        finish("")
                    }
                    .addOnCanceledListener { finish("") }
            }
        } catch (t: Throwable) {
            // 识别器被 close / 位图被回收等异常：不上抛，交由调用方按「识别失败」提示
            LogUtil.e("OCR 调用异常", t)
            ""
        }
    }

    /** 释放识别器（幂等）。下次 [recognize] 会自动重建。 */
    fun close() {
        synchronized(lock) {
            runCatching { recognizer?.close() }
                .onFailure { LogUtil.w("OCR 识别器关闭异常: ${it.javaClass.simpleName}") }
            recognizer = null
            LogUtil.i("OCR 识别器已释放")
        }
    }
}
