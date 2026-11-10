package com.channelsurf.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.channelsurf.MainActivity
import com.channelsurf.R

/**
 * While a channel is playing on the TV, keeps Channel Surfer awake with the phone locked or in a pocket:
 * a foreground service plus Wi-Fi and CPU locks. The app can then keep the TV's schedule topped up, follow
 * planned channel changes, and move to the next program on Google Cast TVs.
 */
class CastKeepAliveService : Service() {
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val tv = intent?.getStringExtra(EXTRA_TV) ?: "your TV"
        val notification = buildNotification(tv)
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
            )
        } catch (e: Exception) {
            // Not allowed to start from the background right now; the TV still plays its own queue.
            stopSelf()
            return START_NOT_STICKY
        }
        acquireLocks()
        return START_STICKY
    }

    private fun acquireLocks() {
        if (wifiLock == null) {
            val wifi = applicationContext.getSystemService(WifiManager::class.java)
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = runCatching { wifi?.createWifiLock(mode, "channel-surfer:tv")?.apply { setReferenceCounted(false); acquire() } }.getOrNull()
        }
        if (wakeLock == null) {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = runCatching { pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "channel-surfer:tv").apply { setReferenceCounted(false); acquire(MAX_LOCK_MS) } }.getOrNull()
        }
    }

    private fun buildNotification(tv: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Playing on TV", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("Channel Surfer is on $tv")
            .setContentText("The channel keeps playing while your phone is locked.")
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    override fun onDestroy() {
        runCatching { wifiLock?.release() }
        runCatching { wakeLock?.release() }
        wifiLock = null
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "channel_surfer_tv"
        private const val NOTIFICATION_ID = 4107
        private const val EXTRA_TV = "tv"
        private const val ACTION_STOP = "stop"
        private const val MAX_LOCK_MS = 24 * 3_600_000L

        fun start(context: Context, tvName: String) {
            val i = Intent(context, CastKeepAliveService::class.java).putExtra(EXTRA_TV, tvName)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i) else context.startService(i)
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, CastKeepAliveService::class.java)) }
        }
    }
}
