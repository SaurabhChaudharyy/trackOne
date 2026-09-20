package app.trackone.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import app.trackone.data.database.NetWorthDao
import app.trackone.data.repository.NetWorthRepository
import app.trackone.notifications.NotificationHelper
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

/**
 * Posts a once-daily notification with how the portfolio's market-priced holdings moved
 * today, and which moved most. Cumulative gain/loss since purchase is deliberately NOT here —
 * it barely changes day to day, so repeating it daily is noise; [WeeklySummaryWorker] carries it.
 *
 * Fetches live quotes at fire time (the previous close is needed for "today"), so it only runs
 * with a network. Fires ~8:30pm IST: Indian markets have closed (a full day's move); US markets
 * are mid-session, so US holdings show their move so far.
 */
@HiltWorker
class DailyDigestWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val netWorthDao: NetWorthDao,
    private val netWorthRepository: NetWorthRepository
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val assets = netWorthDao.getAllAssetsSync()
            val measurable = assets.filter { it.assetType in NetWorthRepository.FETCHABLE_TYPES && it.name.isNotBlank() }
            // Nothing market-priced (e.g. only cash/MF) — no daily move exists; the weekly
            // summary still covers this portfolio.
            if (measurable.isEmpty()) return Result.success()

            val quotes = netWorthRepository.fetchQuotesFor(assets)
            val content = computeDailyDigest(assets, quotes)
                // Every quote failed or had no previous close: retry rather than post a fake "0%".
                ?: return if (runAttemptCount < 3) Result.retry() else Result.success()

            NotificationHelper.notifyDigest(applicationContext, formatDailyBody(content))
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
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
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
        internal fun millisUntilNextDigestTime(now: LocalDateTime = LocalDateTime.now(ZoneId.systemDefault())): Long {
            var next = LocalDateTime.of(now.toLocalDate(), LocalTime.of(DIGEST_HOUR, DIGEST_MINUTE))
            if (!next.isAfter(now)) next = next.plusDays(1)
            return ChronoUnit.MILLIS.between(now, next)
        }
    }
}
