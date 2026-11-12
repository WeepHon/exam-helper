package com.examhelper.a11y

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.examhelper.core.LogUtil

/**
 * 无障碍服务：读取当前窗口内的所有可见文本。
 *
 * 与悬浮球同进程，通过 [instance] 直接调用 [collectCurrentText]。
 * 优点：无需截屏/OCR 授权，直接拿到控件文本（比 OCR 更准）。
 */
class AnswerAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: AnswerAccessibilityService? = null
            private set

        /** 当前前台应用包名（由无障碍事件维护） */
        @Volatile
        var currentPackage: String? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        LogUtil.i("无障碍服务已连接 onServiceConnected")
    }

    private var lastPackage: String? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 防御：任何事件处理异常都不应导致服务被系统标记为「无法运行」
        try {
            event?.packageName?.toString()?.let { pkg ->
                if (pkg != lastPackage) {
                    lastPackage = pkg
                    LogUtil.i("无障碍事件, 前台包名=$pkg")
                }
                currentPackage = pkg
            }
        } catch (t: Throwable) {
            LogUtil.e("无障碍事件处理异常", t)
        }
    }

    override fun onInterrupt() {
        LogUtil.i("无障碍服务 onInterrupt")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        LogUtil.i("无障碍服务 onUnbind（服务被解除绑定）")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        LogUtil.i("无障碍服务 onDestroy")
        super.onDestroy()
    }

    /** 遍历当前活动窗口，收集所有可见文本 */
    fun collectCurrentText(maxLen: Int = 4000): String {
        return try {
            val sb = StringBuilder()
            val root = rootInActiveWindow
            if (root != null) {
                collect(root, sb)
                root.recycle()
            } else {
                // 某些机型/场景 rootInActiveWindow 为 null，退回遍历所有窗口
                for (w in windows) {
                    val r = w?.root ?: continue
                    collect(r, sb)
                    r.recycle()
                }
            }
            if (sb.length > maxLen) sb.setLength(maxLen)
            sb.toString()
        } catch (t: Throwable) {
            LogUtil.e("collectCurrentText 异常", t)
            ""
        }
    }

    private fun collect(node: AccessibilityNodeInfo, sb: StringBuilder) {
        try {
            val text = node.text?.toString()?.trim()
            if (!text.isNullOrEmpty() && text.length in 2..300) {
                sb.append(text).append('\n')
            }
            // WebView / 图片等节点的文字常放在 contentDescription 里
            val desc = node.contentDescription?.toString()?.trim()
            if (!desc.isNullOrEmpty() && desc.length in 2..300 && desc != text) {
                sb.append(desc).append('\n')
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                collect(child, sb)
                child.recycle()
            }
        } catch (t: Throwable) {
            // 单个节点解析失败不影响整体
        }
    }
}
