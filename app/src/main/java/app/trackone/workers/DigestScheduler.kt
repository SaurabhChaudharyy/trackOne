package app.trackone.workers

import android.content.Context
import app.trackone.ui.settings.DigestFrequency
import app.trackone.ui.settings.DigestPrefs

/**
 * Lines the digest workers up with what Settings says: nothing when the digest is off, the two
 * market-close digests when it is daily, the Sunday summary when it is weekly. Only one of the two
 * is ever queued, so switching frequency cancels the other.
 */
object DigestScheduler {

    /**
     * [keepExisting] is for app start, which must not replace a run WorkManager is about to start
     * (see [DailyDigestWorker.schedule]); Settings changes pass false so they take effect at once.
     */
    fun apply(context: Context, keepExisting: Boolean) {
        if (!DigestPrefs.isEnabled(context)) {
            cancel(context)
            return
        }
        when (DigestPrefs.frequency(context)) {
            DigestFrequency.DAILY -> {
                WeeklySummaryWorker.cancel(context)
                DigestMarket.entries.forEach { DailyDigestWorker.schedule(context, it, keepExisting) }
            }
            DigestFrequency.WEEKLY -> {
                DailyDigestWorker.cancelAll(context)
                WeeklySummaryWorker.schedule(context)
            }
        }
    }

    fun cancel(context: Context) {
        DailyDigestWorker.cancelAll(context)
        WeeklySummaryWorker.cancel(context)
    }
}
