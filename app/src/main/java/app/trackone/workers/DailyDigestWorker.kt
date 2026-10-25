package app.trackone.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import app.trackone.data.database.NetWorthDao
import app.trackone.data.repository.NetWorthRepository
import app.trackone.data.repository.StockRepository
import app.trackone.notifications.NotificationHelper
import app.trackone.ui.settings.DigestFrequency
import app.trackone.ui.settings.DigestPrefs
import app.trackone.utils.Resource
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Posts one market's close: its indices, how the user's holdings in that market moved today and which
 * moved most. There is one run per market per weekday, [DigestMarket.DIGEST_DELAY] after its close
 * (Indian, then US), which is the "daily" digest. Cumulative gain/loss since purchase is deliberately
 * NOT here — it barely changes day to day; [WeeklySummaryWorker] carries it.
 *
 * Fetches live quotes at fire time (the previous close is needed for "today"), so it only runs with a
 * network. A day the market did not trade (an exchange holiday) posts nothing.
 */
@HiltWorker
class DailyDigestWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val netWorthDao: NetWorthDao,
    private val netWorthRepository: NetWorthRepository,
    private val stockRepository: StockRepository
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val market = DigestMarket.fromKey(inputData.getString(KEY_MARKET)) ?: return Result.success()

        val outcome = try {
            postDigest(market)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
        }

        // Each run lines up the next one: a fixed 24h period would drift off the close when the
        // market's clocks change. A run that is going to be retried leaves that to its last attempt.
        if (outcome != Result.retry() && isStillWanted(applicationContext)) {
            schedule(applicationContext, market, keepExisting = false)
        }
        return outcome
    }

    private suspend fun postDigest(market: DigestMarket): Result {
        val indexQuotes = market.indices.mapNotNull { index ->
            (stockRepository.fetchFreshQuote(index.symbol) as? Resource.Success)?.data?.let { index to it }
        }

        // Yahoo gives a holiday's quote the last session that ran, so a stale one means no digest.
        val nowSec = Instant.now().epochSecond
        val sessionEnds = indexQuotes.map { it.second.sessionEnd }.filter { it > 0L }
        if (sessionEnds.isNotEmpty() && sessionEnds.none { marketTradedToday(it, nowSec) }) return Result.success()

        val holdings = netWorthDao.getAllAssetsSync()
            .filter { it.assetType == market.assetType && it.name.isNotBlank() }
        val content = if (holdings.isEmpty()) null
        else computeDailyDigest(holdings, netWorthRepository.fetchQuotesFor(holdings))

        // Nothing came back: retry rather than post a bare title (or a fake "0%").
        if (indexQuotes.isEmpty() && content == null) {
            return if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
        }

        val indices = indexQuotes.map { (index, quote) -> IndexMove(index.label, quote.changePercent) }
        NotificationHelper.notifyDigest(applicationContext, market, formatMarketDigest(market, indices, content))
        return Result.success()
    }

    companion object {
        /** The old single 8:30pm periodic job, cancelled so an upgraded install doesn't keep running it. */
        private const val LEGACY_WORK_NAME = "daily_digest_worker"
        private const val KEY_MARKET = "market"
        private const val MAX_RETRIES = 3

        /**
         * Lines up [market]'s next digest. WorkManager can't promise a wall-clock fire time — it may
         * batch or delay for Doze/battery optimization — so this is "around" the digest time, not exact.
         * AlarmManager + exact alarms were considered and rejected: re-requesting
         * SCHEDULE_EXACT_ALARM/USE_EXACT_ALARM (removed as unused permissions) purely for
         * cosmetic notification-timing precision on a non-critical feature isn't worth the
         * Play Store permission-justification overhead.
         *
         * [keepExisting] leaves a queued run alone: app start must, since WorkManager starts the app
         * to run a worker, and replacing the work then would cancel the very run that started it.
         */
        internal fun schedule(context: Context, market: DigestMarket, keepExisting: Boolean) {
            val workManager = WorkManager.getInstance(context)
            workManager.cancelUniqueWork(LEGACY_WORK_NAME)

            val delay = Duration.between(Instant.now(), market.nextDigest(Instant.now()).toInstant())
            val request = OneTimeWorkRequestBuilder<DailyDigestWorker>()
                .setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(KEY_MARKET to market.key))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()

            workManager.enqueueUniqueWork(
                market.workName,
                if (keepExisting) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE,
                request
            )
        }

        internal fun cancelAll(context: Context) {
            val workManager = WorkManager.getInstance(context)
            workManager.cancelUniqueWork(LEGACY_WORK_NAME)
            DigestMarket.entries.forEach { workManager.cancelUniqueWork(it.workName) }
        }

        /** The user may have switched the digest off, or to weekly, while a run was in flight. */
        private fun isStillWanted(context: Context) =
            DigestPrefs.isEnabled(context) && DigestPrefs.frequency(context) == DigestFrequency.DAILY
    }
}
