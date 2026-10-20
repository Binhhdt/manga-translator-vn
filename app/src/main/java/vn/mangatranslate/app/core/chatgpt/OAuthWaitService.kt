package vn.mangatranslate.app.core.chatgpt

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Dịch vụ tiền cảnh CHỈ chạy trong lúc chờ trình duyệt gọi về 127.0.0.1.
 *
 * Lý do: khi Chrome hiện lên, ứng dụng xuống nền. Android 11+ có thể "đóng băng" tiến trình nền đã cache
 * (cached apps freezer) và Android 15 có thể chặn mạng của ứng dụng nền; khi đó listener không accept()
 * được hoặc gói TCP tới cổng bị bỏ → Chrome báo ERR_CONNECTION_TIMED_OUT. Dịch vụ tiền cảnh giữ tiến trình
 * ở mức ưu tiên tiền cảnh trong thời gian ngắn này. Listener tự nó không nằm trong dịch vụ hay Activity,
 * nên không bị đóng khi Activity dừng.
 */
class OAuthWaitService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Đăng nhập ChatGPT", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Đang chờ đăng nhập ChatGPT")
            .setContentText("Hoàn tất đăng nhập trong trình duyệt rồi quay lại ứng dụng.")
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, n, type)
        Log.i(TAG, "FOREGROUND_SERVICE_STARTED")
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "FOREGROUND_SERVICE_STOPPED")
        super.onDestroy()
    }

    companion object {
        const val TAG = "MTV-OAuth"
        private const val CHANNEL = "oauth_wait"
        private const val NOTIF_ID = 4455

        /** @return null nếu thành công, hoặc mô tả lỗi (lớp + thông báo). */
        fun start(ctx: Context): String? = try {
            ContextCompat.startForegroundService(ctx, Intent(ctx, OAuthWaitService::class.java))
            null
        } catch (e: Exception) {
            "${e.javaClass.name}: ${e.message}"
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, OAuthWaitService::class.java))
        }
    }
}
