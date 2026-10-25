package app.trackone.data.repository

import android.util.Log
import androidx.room.withTransaction
import app.trackone.data.database.FinanceDatabase
import app.trackone.data.database.NetWorthDao
import app.trackone.data.database.NetWorthTransactionDao
import app.trackone.data.database.WatchlistDao
import app.trackone.data.database.WatchlistGroupDao
import app.trackone.data.database.WatchlistGroupEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Summary returned after a local data wipe, so the UI can confirm what was actually cleared. */
data class ClearStats(val watchlistItems: Int, val assets: Int, val transactions: Int)

/**
 * Wipes on-device watchlists and net worth data for a "fresh start" — entirely separate
 * from [CloudBackupRepository]: this never touches Firestore, so a signed-in user's cloud
 * backup survives a local clear (and can be pulled back down with Restore afterwards).
 */
@Singleton
class LocalDataRepository @Inject constructor(
    private val database: FinanceDatabase,
    private val watchlistDao: WatchlistDao,
    private val watchlistGroupDao: WatchlistGroupDao,
    private val netWorthDao: NetWorthDao,
    private val netWorthTransactionDao: NetWorthTransactionDao,
    private val brokerCsvRepository: BrokerCsvRepository
) {
    companion object {
        private const val TAG = "LocalData"
        private const val DEFAULT_GROUP_ID = 1L
        private const val DEFAULT_GROUP_NAME = "My Watchlist"
    }

    suspend fun clearAllLocalData(): Result<ClearStats> = withContext(Dispatchers.IO) {
        try {
            val watchlistCountBefore = watchlistDao.getWatchlistSync().size
            val assetsCountBefore = netWorthDao.getAllAssetsSync().size
            val transactionsCountBefore = netWorthTransactionDao.getAllTransactionsSync().size

            database.withTransaction {
                watchlistDao.clearWatchlist()
                val existingGroups = watchlistGroupDao.getAllGroupsSync()
                for (g in existingGroups) {
                    watchlistGroupDao.deleteGroup(g.id)
                }
                // Re-seed the default group so the watchlist screen has somewhere to land
                // rather than showing a broken/groupless empty state.
                watchlistGroupDao.insertGroup(
                    WatchlistGroupEntity(id = DEFAULT_GROUP_ID, name = DEFAULT_GROUP_NAME, position = 0, createdAt = System.currentTimeMillis())
                )
                netWorthDao.deleteAllAssets()
                netWorthTransactionDao.deleteAllTransactions()
            }

            // Forget which broker files were already imported, so re-uploading the same
            // file after a clear isn't rejected as a duplicate — the data it holds is gone.
            brokerCsvRepository.clearImportHistory()

            val stats = ClearStats(watchlistCountBefore, assetsCountBefore, transactionsCountBefore)
            Log.d(TAG, "clearAllLocalData: success – $stats")
            Result.success(stats)
        } catch (e: Exception) {
            Log.e(TAG, "clearAllLocalData: failed", e)
            Result.failure(e)
        }
    }
}
