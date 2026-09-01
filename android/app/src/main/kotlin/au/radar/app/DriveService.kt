package au.radar.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Holds the process alive while driving.
 *
 * The work itself lives in [DriveViewModel]; this service exists so Android does
 * not kill the app the moment the screen goes off, which is the normal state of
 * a phone in a cradle. Its notification is the honest signal that location is
 * being used — which is also why background-location permission is unnecessary.
 */
class DriveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        // If the system reclaims us mid-drive, come back rather than vanishing.
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.drive_channel_name),
                // LOW: visible and honest, but it must never buzz. The warnings
                // are the audio; the notification is just a status line.
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.drive_channel_description)
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.drive_notification_title))
            .setContentText(getString(R.string.drive_notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "drive"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            val intent = Intent(context, DriveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DriveService::class.java))
        }
    }
}
