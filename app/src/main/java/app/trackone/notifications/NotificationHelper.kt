package app.trackone.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.trackone.R
import app.trackone.ui.main.MainActivity
import app.trackone.utils.GainLoss

/**
 * All notification-building lives here so a second notification type (e.g. the price-threshold
 * alerts deferred past this round) only needs its own channel constant + builder function,
 * not a restructure of this file.
 */
object NotificationHelper {

    const val CHANNEL_ID_DIGEST = "daily_digest"
    private const val NOTIFICATION_ID_DIGEST = 1001

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
                description = "A once-a-day summary of your portfolio's best/worst performers and overall P&L."
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

    /**
     * [best]/[worst] are per-asset [GainLoss] paired with a display name; either may be null
     * when there are no holdings with a known symbol/name to rank.
     */
    fun notifyDigest(
        context: Context,
        portfolioPctChange: Double,
        best: Pair<String, GainLoss>?,
        worst: Pair<String, GainLoss>?
    ) {
        val body = buildString {
            append("Portfolio ${formatPct(portfolioPctChange)} overall.")
            if (best != null) append(" Best: ${best.first} ${formatPct(best.second.pctChange)}.")
            if (worst != null) append(" Worst: ${worst.first} ${formatPct(worst.second.pctChange)}.")
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_DIGEST)
            .setSmallIcon(R.drawable.ic_notification_digest)
            .setContentTitle("Your daily portfolio digest")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(mainActivityPendingIntent(context))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        notify(context, NOTIFICATION_ID_DIGEST, notification)
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

    private fun formatPct(pct: Double): String {
        val sign = if (pct >= 0) "+" else ""
        return "$sign${"%.2f".format(pct)}%"
    }
}
