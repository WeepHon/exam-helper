package com.examhelper

import android.app.Application
import android.os.Build
import android.os.Process
import com.examhelper.core.LogUtil

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        LogUtil.init(this)
        LogUtil.i("========== App 启动 ==========")
        LogUtil.i("package=${packageName} pid=${Process.myPid()} sdk=${Build.VERSION.SDK_INT} model=${Build.MODEL}")
        LogUtil.i("日志文件: ${LogUtil.logPath}")

        // 全局崩溃捕获：任何未捕获异常都写入本地日志，方便排查
        val default = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            LogUtil.e("未捕获崩溃 thread=${thread.name}", throwable)
            default?.uncaughtException(thread, throwable)
        }
    }
}
