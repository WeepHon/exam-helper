package com.examhelper.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle

/**
 * 透明 Activity：负责弹出系统"屏幕投射/录制"授权框，
 * 拿到结果后启动 [ScreenCaptureService] 抓取一帧并自行结束。
 */
class CapturePermissionActivity : Activity() {

    companion object {
        const val EXTRA_LAUNCH_FROM = "launch_from"
        const val ACTION_CAPTURE_CANCELED = "com.examhelper.action.CAPTURE_CANCELED"

        /**
         * @param attemptId 本次识别请求序号，透传给 [ScreenCaptureService]，
         *   取消时也随取消广播回传，悬浮球据此只复位本次请求。
         */
        fun start(context: Context, attemptId: Int = 0) {
            val i = Intent(context, CapturePermissionActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            i.putExtra(ScreenCaptureService.EXTRA_ATTEMPT_ID, attemptId)
            context.startActivity(i)
        }
    }

    private val reqCode = 4001
    private var attemptId = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        attemptId = intent?.getIntExtra(ScreenCaptureService.EXTRA_ATTEMPT_ID, 0) ?: 0
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), reqCode)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqCode) {
            if (resultCode == RESULT_OK && data != null) {
                val svc = Intent(this, ScreenCaptureService::class.java)
                svc.putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                svc.putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
                svc.putExtra(ScreenCaptureService.EXTRA_ATTEMPT_ID, attemptId)
                startForegroundService(svc)
            } else {
                // 用户取消/拒绝授权：广播通知悬浮球复位，避免其一直卡在 busy 状态
                val cancel = Intent(ACTION_CAPTURE_CANCELED)
                cancel.setPackage(packageName)
                cancel.putExtra(ScreenCaptureService.EXTRA_ATTEMPT_ID, attemptId)
                sendBroadcast(cancel)
            }
        }
        finish()
        overridePendingTransition(0, 0)
    }
}
