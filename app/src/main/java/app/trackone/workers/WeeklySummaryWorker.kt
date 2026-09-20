package app.trackone.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import app.trackone.data.database.NetWorthDao
import app.trackone.notifications.NotificationHelper
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.TimeUnit

/**
 * Posts a once-a-week notification with the portfolio's total gain/loss since purchase and its
 * best/worst holdings. Uses stored values only (no network), so it works for every asset type —
 * including MF/cash/bank, which have no live price. Sunday morning, when markets are closed and
 * the numbers reflect the week's final prices (as last refreshed by the background update).
 */
@HiltWorker
class WeeklySummaryWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val netWorthDao: NetWorthDao
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val assets = netWorthDao.getAllAssetsSync()
            if (assets.isEmpty()) return Result.success()

            NotificationHelper.notifyWeeklySummary(applicationContext, formatWeeklyBody(computeDigestContent(assets)))
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val WORK_NAME = "weekly_summary_worker"
        private val SUMMARY_DAY = DayOfWeek.SUNDAY
        private const val SUMMARY_HOUR = 10

        /** "Around Sunday 10am" — WorkManager may delay for Doze, same trade-off as the daily digest. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<WeeklySummaryWorker>(7, TimeUnit.DAYS)
                .setInitialDelay(millisUntilNextSummaryTime(), TimeUnit.MILLISECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        /** [now] is overridable so this is testable without depending on when the test runs. */
        internal fun millisUntilNextSummaryTime(now: LocalDateTime = LocalDateTime.now(ZoneId.systemDefault())): Long {
            var next = now.toLocalDate().with(TemporalAdjusters.nextOrSame(SUMMARY_DAY))
                .atTime(LocalTime.of(SUMMARY_HOUR, 0))
            if (!next.isAfter(now)) next = next.plusWeeks(1)
            return ChronoUnit.MILLIS.between(now, next)
        }
    }
}
