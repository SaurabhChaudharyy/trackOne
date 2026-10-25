package app.trackone.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.trackone.R
import app.trackone.ui.main.MainActivity
import app.trackone.workers.DigestMarket
import app.trackone.workers.DigestMessage
import app.trackone.workers.MoveDirection
import app.trackone.workers.moveSpans
import app.trackone.workers.subjectSpans

/**
 * All notification-building lives here so a second notification type (e.g. the price-threshold
 * alerts deferred past this round) only needs its own channel constant + builder function,
 * not a restructure of this file.
 */
object NotificationHelper {

    /** The Indian market's close. (The id predates the US one, from when there was a single daily digest.) */
    const val CHANNEL_ID_DIGEST = "daily_digest"
    private const val NOTIFICATION_ID_DIGEST = 1001

    /** The US market's close: it lands in the small hours in India, so its channel makes no sound. */
    const val CHANNEL_ID_DIGEST_US = "daily_digest_us"
    private const val NOTIFICATION_ID_DIGEST_US = 1004

    const val CHANNEL_ID_WEEKLY = "weekly_summary"
    private const val NOTIFICATION_ID_WEEKLY = 1003

    const val CHANNEL_ID_PORTFOLIO_REMINDER = "portfolio_reminder"
    private const val NOTIFICATION_ID_PORTFOLIO_REMINDER = 1002

    /** More than a digest ever has (a portfolio line, indices, top and lagging), so nothing is cut. */
    private const val MAX_BODY_LINES = 8

    /** Idempotent and cheap — safe to call unconditionally on every app start. */
    fun ensureChannelsCreated(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID_DIGEST,
                "Indian market close",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "An hour after the Indian market closes: Nifty 50, Sensex, and how your Indian stocks moved."
            }
        )

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID_DIGEST_US,
                "US market close",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "An hour after the US market closes: S&P 500, Nasdaq, and how your US stocks moved. " +
                    "Silent, since it arrives in the small hours in India."
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

    internal fun notifyDigest(context: Context, market: DigestMarket, message: DigestMessage) = when (market) {
        DigestMarket.INDIA -> postSummary(context, CHANNEL_ID_DIGEST, NOTIFICATION_ID_DIGEST, message, NotificationCompat.PRIORITY_DEFAULT)
        DigestMarket.US -> postSummary(context, CHANNEL_ID_DIGEST_US, NOTIFICATION_ID_DIGEST_US, message, NotificationCompat.PRIORITY_LOW)
    }

    internal fun notifyWeeklySummary(context: Context, message: DigestMessage) =
        postSummary(context, CHANNEL_ID_WEEKLY, NOTIFICATION_ID_WEEKLY, message, NotificationCompat.PRIORITY_DEFAULT)

    /**
     * Collapsed, the lines run together on two at most; expanded, each has its own. The body is a custom
     * layout in the system's own frame because a standard notification ignores colour spans, so gains
     * and losses could not be coloured in it.
     */
    private fun postSummary(context: Context, channelId: String, id: Int, message: DigestMessage, priority: Int) {
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification_digest)
            // The plain text still stands in where the custom layout isn't shown (lock screen, wearables).
            .setContentTitle(message.title)
            .setContentText(message.summary)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(digestViews(context, message.title, styled(context, message.summary), maxLines = 2))
            .setCustomBigContentView(digestViews(context, message.title, styled(context, message.body), maxLines = MAX_BODY_LINES))
            .setContentIntent(mainActivityPendingIntent(context))
            .setAutoCancel(true)
            .setPriority(priority)
            .build()

        notify(context, id, notification)
    }

    private fun digestViews(context: Context, title: String, text: CharSequence, maxLines: Int) =
        RemoteViews(context.packageName, R.layout.notification_digest).apply {
            setTextViewText(R.id.digest_title, title)
            setTextViewText(R.id.digest_text, text)
            setInt(R.id.digest_text, "setMaxLines", maxLines)
        }

    /**
     * [text] with each move in the app's gain or loss colour and what it is about in bold. The colours
     * are the ones the screens use, and resolve to their lighter dark-mode variants when the phone is in
     * dark mode, as the shade then is.
     */
    private fun styled(context: Context, text: String): CharSequence {
        val moves = moveSpans(text)
        if (moves.isEmpty()) return text
        val gain = ContextCompat.getColor(context, R.color.gain)
        val loss = ContextCompat.getColor(context, R.color.loss)
        return SpannableString(text).apply {
            for (move in moves) {
                val color = when (move.direction) {
                    MoveDirection.GAIN -> gain
                    MoveDirection.LOSS -> loss
                    MoveDirection.FLAT -> continue
                }
                setSpan(ForegroundColorSpan(color), move.start, move.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            for (subject in subjectSpans(text)) {
                setSpan(StyleSpan(Typeface.BOLD), subject.first, subject.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    /**
     * A plain nudge — no portfolio data needed, so unlike the digests this can be posted
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
