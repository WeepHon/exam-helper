package com.examhelper.core

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 统一日志工具：三通道输出
 *  1. Logcat（tag = ExamHelper）
 *  2. LSPosed 日志（反射调用 XposedBridge.log，无 Xposed 环境时静默失败）
 *  3. 本地文件（app 私有/外部 files 目录下 logs/examhelper.log）
 */
object LogUtil {

    private const val TAG = "ExamHelper"
    private val sdf = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var logFile: File? = null

    @Volatile
    var logPath: String = ""
        private set

    fun init(context: Context) {
        try {
            var dir = context.getExternalFilesDir("logs")
            if (dir == null) dir = File(context.filesDir, "logs")
            if (!dir.exists()) dir.mkdirs()
            logFile = File(dir, "examhelper.log")
            logPath = logFile?.absolutePath ?: ""
            // 超过 2MB 轮转，避免无限膨胀
            if ((logFile?.length() ?: 0) > 2 * 1024 * 1024) {
                logFile?.delete()
            }
        } catch (t: Throwable) {
            // ignore
        }
    }

    fun i(msg: String) = write("I", msg, null)
    fun w(msg: String) = write("W", msg, null)
    fun e(msg: String, t: Throwable? = null) = write("E", msg, t)

    /** 读取日志文件末尾内容，用于复制/导出 */
    fun readLog(maxChars: Int = 20000): String {
        return try {
            val f = logFile ?: return "（日志文件未初始化）"
            val all = f.readText()
            if (all.length > maxChars) "…(已截断，仅保留末尾)…\n" + all.takeLast(maxChars) else all
        } catch (t: Throwable) {
            "读取日志失败: ${t.message}"
        }
    }

    /** 清空本地日志文件内容 */
    fun clear() {
        try {
            logFile?.writeText("")
        } catch (_: Throwable) {
            // ignore
        }
    }

    private fun write(level: String, msg: String, t: Throwable?) {
        val time = try { sdf.format(Date()) } catch (_: Throwable) { "" }
        val body = t?.let { "$msg\n${Log.getStackTraceString(it)}" } ?: msg
        val line = "$time [$level] $body"
        Log.d(TAG, line)
        xposedLog(line)
        try { logFile?.appendText(line + "\n") } catch (_: Throwable) {}
    }

    private fun xposedLog(line: String) {
        try {
            val c = Class.forName("de.robv.android.xposed.XposedBridge")
            c.getMethod("log", String::class.java).invoke(null, line)
        } catch (_: Throwable) {
            // 无 Xposed 环境，忽略
        }
    }
}
