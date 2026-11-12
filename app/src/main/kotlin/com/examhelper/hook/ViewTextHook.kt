package com.examhelper.hook

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

/**
 * "精准模式" Hook：
 *
 * 在目标 App 进程内：
 *  1. 注册一个动态 BroadcastReceiver，响应主模块发出的 `READ_TEXT` 请求；
 *  2. 收到请求后，遍历当前 Activity 的视图树，把所有 TextView/EditText 文本
 *     打包发回主模块（action = `TEXT_RESULT`），主模块据此去题库匹配。
 *
 * 相比屏幕 OCR，直接读控件文本零误差、无需截屏授权。
 */
object ViewTextHook {

    const val ACTION_READ_TEXT = "com.examhelper.action.READ_TEXT"
    const val ACTION_TEXT_RESULT = "com.examhelper.action.TEXT_RESULT"
    const val EXTRA_TEXT = "extra_text"
    const val EXTRA_PKG = "extra_pkg"

    /** 识别请求序号：主模块下发、Hook 原样带回，主模块据此丢弃过期回传 */
    const val EXTRA_ATTEMPT_ID = "extra_attempt_id"

    /** 主模块包名（联网版 com.examhelper / 仅本地版 com.examhelper.local），回传时按其定向 */
    const val EXTRA_REPLY_PKG = "extra_reply_pkg"

    @Volatile
    private var currentActivity: Activity? = null

    fun hook(lpparam: LoadPackageParam) {
        val classLoader = lpparam.classLoader ?: return
        val pkg = lpparam.packageName

        // 1) 追踪当前 Activity
        runCatching {
            XposedHelpers.findAndHookMethod(
                Activity::class.java, "onResume", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        currentActivity = param.thisObject as? Activity
                    }
                }
            )
        }

        // 2) 在 Application 初始化后注册响应广播
        runCatching {
            XposedHelpers.findAndHookMethod(
                "android.app.Application", classLoader, "onCreate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val app = param.thisObject as? Context ?: return
                        registerReceiver(app, pkg)
                    }
                }
            )
        }
    }

    private fun registerReceiver(app: Context, pkg: String) {
        val filter = IntentFilter(ACTION_READ_TEXT)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != ACTION_READ_TEXT) return
                // 仅响应显式发给本包名的请求
                if (intent.getStringExtra(EXTRA_PKG) != pkg) return
                val text = collectText()
                val reply = Intent(ACTION_TEXT_RESULT)
                // 不能写死 com.examhelper：仅本地版包名是 com.examhelper.local，
                // 写死会让仅本地版的「精准模式」永远收不到回传。
                val replyPkg = intent.getStringExtra(EXTRA_REPLY_PKG) ?: "com.examhelper"
                reply.setPackage(replyPkg)
                reply.putExtra(EXTRA_TEXT, text)
                reply.putExtra(EXTRA_PKG, pkg)
                reply.putExtra(EXTRA_ATTEMPT_ID, intent.getIntExtra(EXTRA_ATTEMPT_ID, -1))
                context.sendBroadcast(reply)
            }
        }
        // 跨应用广播需要 EXPORTED；不同 Android 版本签名不同，用反射兼容
        runCatching {
            val m = Context::class.java.getMethod(
                "registerReceiver",
                BroadcastReceiver::class.java,
                IntentFilter::class.java,
                Int::class.javaPrimitiveType
            )
            m.invoke(app, receiver, filter, 0x2) // Context.RECEIVER_EXPORTED == 2
        }.onFailure {
            runCatching { app.registerReceiver(receiver, filter) }
        }
    }

    /** 在当前 Activity 的 UI 线程遍历视图树，收集可见文本（onReceive 运行于主线程） */
    private fun collectText(): String {
        val act = currentActivity ?: return ""
        val sb = StringBuilder()
        runCatching {
            val root = act.window?.decorView as? ViewGroup
            if (root != null) collect(root, sb)
        }
        return sb.toString()
    }

    private fun collect(view: View, sb: StringBuilder) {
        if (view is TextView) {
            val t = view.text?.toString()?.trim()
            if (!t.isNullOrEmpty() && t.length in 3..200) {
                sb.append(t).append('\n')
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                runCatching { collect(view.getChildAt(i), sb) }
            }
        }
    }
}
