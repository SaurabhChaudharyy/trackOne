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

    /** Idempotent and cheap — safe to call unconditionally on every app start. */
    fun ensureChannelsCreated(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID_DIGEST,
            "Daily Digest",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "A once-a-day summary of your portfolio's best/worst performers and overall P&L."
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
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

        val contentIntent = Intent(context, MainActivity::class.java)
        val pendingIntent = android.app.PendingIntent.getActivity(
            context, 0, contentIntent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_DIGEST)
            .setSmallIcon(R.drawable.ic_notification_digest)
            .setContentTitle("Your daily portfolio digest")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        // Caller (DailyDigestWorker) only runs once POST_NOTIFICATIONS has been granted
        // (digest is only scheduled after the user opts in via the Settings toggle, which
        // requests the permission first) — this check is a defensive last line, not the
        // primary gate.
        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_DIGEST, notification)
        }
    }

    private fun formatPct(pct: Double): String {
        val sign = if (pct >= 0) "+" else ""
        return "$sign${"%.2f".format(pct)}%"
    }
}
