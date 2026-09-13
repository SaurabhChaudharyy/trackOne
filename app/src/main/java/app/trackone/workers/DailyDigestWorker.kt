package app.trackone.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import app.trackone.data.database.NetWorthDao
import app.trackone.notifications.NotificationHelper
import app.trackone.utils.PortfolioGainLoss
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

/**
 * Posts a once-daily notification summarizing the portfolio's best/worst performing holdings
 * and overall P&L. Deliberately uses cumulative gain/loss since purchase (via
 * [PortfolioGainLoss]) rather than day-over-day price movement — that works uniformly across
 * every asset type (including MF/cash/bank, which have no live-price tracking at all) and
 * needs no network call at digest-fire time, at the cost of likely repeating the same 1-2
 * holdings most days. Confirmed acceptable for v1.
 */
@HiltWorker
class DailyDigestWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val netWorthDao: NetWorthDao
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val assets = netWorthDao.getAllAssetsSync()
            if (assets.isEmpty()) return Result.success()

            val portfolioPctChange = PortfolioGainLoss.compute(assets).pctChange

            val ranked = assets
                .filter { it.name.isNotBlank() }
                .map { it.name to PortfolioGainLoss.computePerAsset(it) }
            val best = ranked.maxByOrNull { it.second.pctChange }
            // Don't show the same single holding as both best and worst.
            val worst = if (ranked.size > 1) ranked.minByOrNull { it.second.pctChange } else null

            NotificationHelper.notifyDigest(applicationContext, portfolioPctChange, best, worst)
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val WORK_NAME = "daily_digest_worker"
        private const val DIGEST_HOUR = 20
        private const val DIGEST_MINUTE = 30

        /**
         * PeriodicWorkRequest can't guarantee a wall-clock fire time — WorkManager may batch
         * or delay it for Doze/battery optimization, so this is "around 8:30pm", not exact.
         * AlarmManager + exact alarms were considered and rejected: re-requesting
         * SCHEDULE_EXACT_ALARM/USE_EXACT_ALARM (removed as unused permissions) purely for
         * cosmetic notification-timing precision on a non-critical feature isn't worth the
         * Play Store permission-justification overhead.
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<DailyDigestWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(millisUntilNextDigestTime(), TimeUnit.MILLISECONDS)
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

        private fun millisUntilNextDigestTime(): Long {
            val zone = ZoneId.systemDefault()
            val now = LocalDateTime.now(zone)
            var next = LocalDateTime.of(LocalDate.now(zone), LocalTime.of(DIGEST_HOUR, DIGEST_MINUTE))
            if (!next.isAfter(now)) next = next.plusDays(1)
            return ChronoUnit.MILLIS.between(now, next)
        }
    }
}
