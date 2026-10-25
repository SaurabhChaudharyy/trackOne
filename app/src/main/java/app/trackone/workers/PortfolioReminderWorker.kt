package app.trackone.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.trackone.notifications.NotificationHelper
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

/**
 * Nudges the user twice a month (the 1st and 15th) to update manually-tracked holdings (cash,
 * bank, gold, mutual funds — nothing NetWorthRepository can refresh on its own) and re-import
 * broker CSVs, since neither happens automatically. Unlike DailyDigestWorker, "twice a month" has
 * no clean fixed interval (months aren't a uniform number of days), so this self-reschedules:
 * each firing posts the notification, then immediately queues the next occurrence as a new
 * one-time request, rather than relying on WorkManager's PeriodicWorkRequest.
 */
@HiltWorker
class PortfolioReminderWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            NotificationHelper.notifyPortfolioReminder(applicationContext)
            schedule(applicationContext)
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val WORK_NAME = "portfolio_reminder_worker"
        private const val REMINDER_HOUR = 10
        private const val REMINDER_MINUTE = 0
        private val REMINDER_DAYS_OF_MONTH = intArrayOf(1, 15)

        /**
         * Same caveat as DailyDigestWorker: a plain delay-based one-time request isn't an exact
         * alarm — WorkManager may fire it somewhat late (Doze/battery optimization), and if the
         * app is force-stopped or uninstalled-and-reinstalled the chain simply stops until
         * Settings (or FinanceApplication.onCreate, if still enabled) calls this again.
         */
        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<PortfolioReminderWorker>()
                .setInitialDelay(millisUntilNextReminderTime(), TimeUnit.MILLISECONDS)
                .build()

            // REPLACE, not APPEND/KEEP: re-enabling from Settings (or a fresh app start) should
            // always mean "recompute the next occurrence," not stack a duplicate chain.
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        /** [now] is overridable so this is testable without depending on when the test runs. */
        internal fun millisUntilNextReminderTime(now: LocalDateTime = LocalDateTime.now(ZoneId.systemDefault())): Long {
            var next = nextReminderDateTimeOnOrAfter(now.toLocalDate())
            if (!next.isAfter(now)) {
                next = nextReminderDateTimeOnOrAfter(now.toLocalDate().plusDays(1))
            }
            return ChronoUnit.MILLIS.between(now, next)
        }

        /** The earliest 1st-or-15th-at-REMINDER_HOUR:REMINDER_MINUTE on or after [fromDate]. */
        internal fun nextReminderDateTimeOnOrAfter(fromDate: LocalDate): LocalDateTime {
            var month = YearMonth.from(fromDate)
            while (true) {
                val candidateDay = REMINDER_DAYS_OF_MONTH.firstOrNull { day ->
                    month.atDay(day) >= fromDate
                }
                if (candidateDay != null) {
                    return LocalDateTime.of(month.atDay(candidateDay), LocalTime.of(REMINDER_HOUR, REMINDER_MINUTE))
                }
                month = month.plusMonths(1)
            }
        }
    }
}
