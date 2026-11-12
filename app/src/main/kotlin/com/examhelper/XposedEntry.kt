package com.examhelper

import com.examhelper.core.LogUtil
import com.examhelper.hook.ViewTextHook
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

/**
 * LSPosed 模块入口。
 *
 * 通用流程（悬浮球 + 无障碍/屏幕OCR）不依赖 Hook 即可工作；
 * 这里的 Hook 用于"精准模式"：在目标 App 进程内直接读取题目控件文本，
 * 并把结果通过广播回传给主模块展示，识别准确率远高于 OCR。
 */
class XposedEntry : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        // 仅注入用户已勾选的目标 App；跳过系统框架与本模块自身
        if (lpparam.packageName == "com.examhelper") return
        if (lpparam.isFirstApplication == false) return

        LogUtil.i("XposedEntry.handleLoadPackage: ${lpparam.packageName}")
        try {
            ViewTextHook.hook(lpparam)
            LogUtil.i("XposedEntry hook 成功: ${lpparam.packageName}")
        } catch (t: Throwable) {
            LogUtil.e("XposedEntry hook 失败: ${lpparam.packageName}", t)
        }
    }
}
