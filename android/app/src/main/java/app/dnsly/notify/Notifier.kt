package app.dnsly.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.dnsly.MainActivity

/** Notification channels and builders. Channels are created once, lazily, on first engine start. */
object Notifier {
    private const val CHANNEL_VPN = "vpn"
    private const val CHANNEL_EVENTS = "events"
    const val VPN_NOTIFICATION_ID = 1
    private const val EVENT_NOTIFICATION_ID = 2

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_VPN, "VPN de DNS", NotificationManager.IMPORTANCE_LOW),
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_EVENTS, "Estado de Dnsly", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    /** Ongoing notification the foreground VPN service must show. */
    fun vpnNotification(ctx: Context): Notification = base(ctx, CHANNEL_VPN)
        .setContentTitle("Dnsly")
        .setContentText("VPN local de DNS activa")
        .setOngoing(true)
        .build()

    /** Replaces the previous event notification; used for state changes that need attention. */
    fun postEvent(ctx: Context, title: String, text: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.notify(EVENT_NOTIFICATION_ID, base(ctx, CHANNEL_EVENTS).setContentTitle(title).setContentText(text).build())
    }

    private fun base(ctx: Context, channel: String): Notification.Builder {
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(ctx, channel)
            .setSmallIcon(ctx.applicationInfo.icon)
            .setContentIntent(open)
    }
}
