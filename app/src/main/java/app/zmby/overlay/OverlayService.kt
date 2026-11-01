package app.zmby.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import app.zmby.R
import app.zmby.log.ZLog

/**
 * Keeps zmby's process in the foreground while the system player covers it and the playback
 * overlay is up (overlay research: 建议集成路径 1). It owns nothing: windows belong to
 * [PlaybackOverlay], which starts this before the player and stops it when the playback ends.
 */
class OverlayService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "播放浮层", NotificationManager.IMPORTANCE_MIN))
        }
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("zmby 播放浮层")
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
        return START_NOT_STICKY
    }

    companion object {
        private const val TAG = "OverlaySvc"
        private const val CHANNEL = "playback_overlay"
        private const val NOTIFICATION_ID = 9529

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, OverlayService::class.java)) }
                .onFailure { ZLog.w(TAG, "overlay service not started", it) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }
}
