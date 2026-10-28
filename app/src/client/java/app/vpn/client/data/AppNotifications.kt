package app.vpn.client.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.vpn.R
import app.vpn.client.MainActivity

/**
 * Posts real device notifications for admin messages — like an SMS, but straight from the app
 * (no SMS gateway or push operator needed). Delivered the moment a broadcast arrives over the
 * live socket, and backfilled on the next refresh for anything missed while the app was closed.
 */
object AppNotifications {
    private const val CHANNEL = "app_messages"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                val ch = NotificationChannel(CHANNEL, "پیام‌های برنامه", NotificationManager.IMPORTANCE_HIGH)
                ch.description = "پیام‌ها و اطلاعیه‌های مدیر برنامه"
                nm.createNotificationChannel(ch)
            }
        }
    }

    fun show(context: Context, title: String, body: String, id: Int) {
        try {
            ensureChannel(context)
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val t = title.ifBlank { "پیام جدید" }
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_bell)
                .setContentTitle(t)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            NotificationManagerCompat.from(context).notify(id, n)
        } catch (e: Exception) {
            // Notifications may be blocked or the permission denied — nothing else to do.
        }
    }
}
