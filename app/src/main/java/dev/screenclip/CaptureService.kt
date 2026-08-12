package dev.screenclip

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.IntentCompat

/**
 * Hosts the MediaProjection freeze, and nothing else — the selection UI and the
 * actions live in [CaptureSession], shared with the accessibility path.
 *
 * This service exists only because MediaProjection demands a foreground service. The
 * accessibility path needs none.
 */
class CaptureService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        private const val CHANNEL_ID = "capture"
        private const val NOTIFICATION_ID = 42
    }

    private var freezer: ProjectionFreezer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Capture", NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Since Android 10 the foreground service must already be running, with type
        // mediaProjection, before getMediaProjection() is called; 14 throws otherwise.
        startForeground(
            NOTIFICATION_ID,
            notification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )

        // The overlay must never rely on the temporary SYSTEM_ALERT_WINDOW grant that
        // MediaProjection.start() hands out and stop() takes back.
        if (!Settings.canDrawOverlays(this)) {
            stop(getString(R.string.need_overlay))
            return START_NOT_STICKY
        }

        val code = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val data = intent?.let {
            IntentCompat.getParcelableExtra(it, EXTRA_RESULT_DATA, Intent::class.java)
        }
        if (code != Activity.RESULT_OK || data == null) {
            stop(getString(R.string.denied))
            return START_NOT_STICKY
        }

        val freezer = ProjectionFreezer(this, code, data)
        this.freezer = freezer
        freezer.freeze { frozen, message ->
            if (frozen == null) {
                stop(message)
                return@freeze
            }
            // The session outlives the projection but not this service: the foreground
            // service is what keeps the process alive while the user adjusts the crop.
            val session = CaptureSession.begin(applicationContext, frozen) { stop(null) }
            if (session == null) {
                frozen.bitmap.recycle()
                stop(null)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        freezer?.release()
        freezer = null
    }

    private fun stop(message: String?) {
        message?.let { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() }
        freezer?.release()
        freezer = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_capture)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setOngoing(true)
            .build()
}
