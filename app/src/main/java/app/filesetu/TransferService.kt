package app.filesetu

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
import androidx.core.content.ContextCompat
import java.net.ServerSocket
import java.security.SecureRandom
import java.util.UUID

/** Keeps the web server alive while the screen is off or the app is in the background. */
class TransferService : Service() {

    companion object {
        private const val CHANNEL = "transfer"
        private const val NOTIF_ID = 1
        private const val ACTION_STOP = "app.filesetu.STOP"

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, TransferService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, TransferService::class.java))
        }
    }

    private var server: TransferServer? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (server == null) {
            val port = freePort()
            Store.pin.value = "%04d".format(SecureRandom().nextInt(10000))
            Store.token = UUID.randomUUID().toString()
            Store.port.value = port
            goForeground()
            try {
                server = TransferServer(applicationContext).also { it.start(port) }
                Store.error.value = null
                Store.status.value = "चालू — PC का इंतज़ार"
                Store.running.value = true
                takeLocks()
            } catch (e: Exception) {
                Store.error.value = "Server शुरू नहीं हुआ: ${e.message}"
                stopSelf()
            }
        } else {
            goForeground()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        val s = server
        server = null
        Thread { s?.stop() }.start()
        releaseLocks()
        Store.running.value = false
        Store.token = ""
        Store.status.value = ""
        super.onDestroy()
    }

    /** Android 15+: data-sync services get a time limit; stop cleanly when it is reached. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Store.status.value = "समय-सीमा पूरी — फिर से चालू करें"
        stopSelf()
    }

    private fun freePort(): Int {
        for (p in listOf(8080, 8081, 8082, 8090, 8888)) {
            val ok = runCatching { ServerSocket(p).use { it.reuseAddress = true } }.isSuccess
            if (ok) return p
        }
        return ServerSocket(0).use { it.localPort }
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "फ़ाइल ट्रांसफ़र", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, TransferService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val addr = localAddresses().firstOrNull()?.let { "http://${it.ip}:${Store.port.value}" } ?: "पता ऐप में देखें"
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_fg)
            .setContentTitle("FileSetu चालू है — PIN ${Store.pin.value}")
            .setContentText(addr)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "बंद करें", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    @Suppress("DEPRECATION")
    private fun takeLocks() {
        val wm = applicationContext.getSystemService(WifiManager::class.java)
        wifiLock = wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "FileSetu:wifi")?.apply {
            setReferenceCounted(false); acquire()
        }
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FileSetu:cpu")?.apply {
            setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L)
        }
    }

    private fun releaseLocks() {
        runCatching { wifiLock?.release() }
        runCatching { wakeLock?.release() }
        wifiLock = null
        wakeLock = null
    }
}
