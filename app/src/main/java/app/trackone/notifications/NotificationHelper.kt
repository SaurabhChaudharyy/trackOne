package app.trackone.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.trackone.R
import app.trackone.ui.main.MainActivity

/**
 * All notification-building lives here so a second notification type (e.g. the price-threshold
 * alerts deferred past this round) only needs its own channel constant + builder function,
 * not a restructure of this file.
 */
object NotificationHelper {

    const val CHANNEL_ID_DIGEST = "daily_digest"
    private const val NOTIFICATION_ID_DIGEST = 1001

    const val CHANNEL_ID_WEEKLY = "weekly_summary"
    private const val NOTIFICATION_ID_WEEKLY = 1003

    const val CHANNEL_ID_PORTFOLIO_REMINDER = "portfolio_reminder"
    private const val NOTIFICATION_ID_PORTFOLIO_REMINDER = 1002

    /** Idempotent and cheap — safe to call unconditionally on every app start. */
    fun ensureChannelsCreated(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID_DIGEST,
                "Daily Digest",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "A once-a-day summary of how your portfolio moved today and its top/lagging holdings."
            }
        )

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID_WEEKLY,
                "Weekly Summary",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "A once-a-week summary of your portfolio's total gain/loss and best/worst holdings."
            }
        )

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID_PORTFOLIO_REMINDER,
                "Portfolio Update Reminder",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "A twice-a-month nudge to update manually-tracked holdings and re-import broker CSVs."
            }
        )
    }

    /** [body] is the finished daily text — see formatDailyBody in DigestFormat.kt. */
    fun notifyDigest(context: Context, body: String) =
        postSummary(context, CHANNEL_ID_DIGEST, NOTIFICATION_ID_DIGEST, "Your daily portfolio digest", body)

    /** [body] is the finished weekly text — see formatWeeklyBody in DigestFormat.kt. */
    fun notifyWeeklySummary(context: Context, body: String) =
        postSummary(context, CHANNEL_ID_WEEKLY, NOTIFICATION_ID_WEEKLY, "Your weekly portfolio summary", body)

    private fun postSummary(context: Context, channelId: String, id: Int, title: String, body: String) {
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification_digest)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(mainActivityPendingIntent(context))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        notify(context, id, notification)
    }

    /**
     * A plain nudge — no portfolio data needed, so unlike [notifyDigest] this can be posted
     * without reading Room first. Content is generic on purpose: "update your holdings and
     * re-import broker CSVs" applies whether or not the user actually has anything stale.
     */
    fun notifyPortfolioReminder(context: Context) {
        val body = "Manually-tracked holdings (cash, bank, gold, mutual funds) and broker CSV " +
            "imports don't update on their own — worth a quick check today."

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_PORTFOLIO_REMINDER)
            .setSmallIcon(R.drawable.ic_notification_digest)
            .setContentTitle("Time to update your portfolio")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(mainActivityPendingIntent(context))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        notify(context, NOTIFICATION_ID_PORTFOLIO_REMINDER, notification)
    }

    private fun mainActivityPendingIntent(context: Context) = android.app.PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java),
        android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
    )

    // Callers (the workers) only run once POST_NOTIFICATIONS has been granted — each
    // notification type is only scheduled after its own Settings toggle requests the
    // permission first — so this check is a defensive last line, not the primary gate.
    private fun notify(context: Context, id: Int, notification: android.app.Notification) {
        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            NotificationManagerCompat.from(context).notify(id, notification)
        }
    }
}
