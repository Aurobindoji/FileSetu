package app.filesetu

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/** "File/text arrived from PC" notifications (separate from the ongoing service notification). */
object Notifier {
    private const val CHANNEL = "received"
    private var nextId = 100

    fun received(ctx: Context, title: String, text: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, tr("PC से आई फ़ाइलें", "Files from PC"), NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val open = PendingIntent.getActivity(
            ctx, 2, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_fg)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(nextId++, n) }
        if (nextId > 150) nextId = 100
    }
}
