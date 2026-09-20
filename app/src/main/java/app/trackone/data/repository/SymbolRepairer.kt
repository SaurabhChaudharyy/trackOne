package app.trackone.data.repository

import android.content.Context
import androidx.core.content.edit
import app.trackone.data.database.AssetType
import app.trackone.data.database.NetWorthAssetEntity
import app.trackone.utils.SymbolUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject
import javax.inject.Singleton

/** One holding to rename, and why — enough to log before anything is changed. */
data class SymbolRepair(
    val assetId: Long,
    val oldName: String,
    val newName: String,
    val basis: MatchBasis,
    val matchedName: String
)

/** Remembers when each holding's ticker lookup was last attempted, so a failing row isn't retried on every refresh. */
@Singleton
class RepairAttempts @Inject constructor(@ApplicationContext private val context: Context) {
    private val prefs by lazy { context.getSharedPreferences("symbol_repair_attempts", Context.MODE_PRIVATE) }

    fun lastAttempt(key: String): Long = prefs.getLong(key, 0L)

    fun record(keys: Collection<String>, atMs: Long) {
        if (keys.isEmpty()) return
        prefs.edit { keys.forEach { putLong(it, atMs) } }
    }
}

/**
 * Repairs holdings stored under something that can't be priced (a company name, a wrong symbol).
 * Fed the holdings whose live-price lookup just FAILED, it looks each one up by ISIN, then name, and
 * proposes a rename only when the result is exactly one clear match.
 *
 * It only proposes: it changes nothing itself, so the caller can log the plan before applying it.
 * Each holding gets one attempt per [RETRY_AFTER_MS]; an attempt where the search couldn't be made
 * (offline) doesn't count, so a bad-connection refresh never locks a row out.
 */
@Singleton
class SymbolRepairer @Inject constructor(
    private val resolver: SymbolResolver,
    private val attempts: RepairAttempts
) {
    suspend fun findRepairs(
        failed: List<NetWorthAssetEntity>,
        all: List<NetWorthAssetEntity>,
        nowMs: Long = System.currentTimeMillis()
    ): List<SymbolRepair> = coroutineScope {
        val due = failed.filter { it.assetType in REPAIRABLE && nowMs - attempts.lastAttempt(key(it)) >= RETRY_AFTER_MS }
        val limiter = Semaphore(MAX_PARALLEL_LOOKUPS)

        val outcomes = due.map { asset ->
            async { limiter.withPermit { asset to resolver.resolve(asset.name, asset.isin, asset.assetType) } }
        }.awaitAll()

        attempts.record(outcomes.filter { it.second !is SymbolResolution.Unavailable }.map { key(it.first) }, nowMs)

        outcomes.mapNotNull { (asset, resolution) ->
            (resolution as? SymbolResolution.Confident)?.let { toRepair(asset, it, all) }
        }
    }

    private fun toRepair(asset: NetWorthAssetEntity, match: SymbolResolution.Confident, all: List<NetWorthAssetEntity>): SymbolRepair? {
        val newKey = canonical(match.symbol, asset.assetType)
        // Already that ticker: the lookup failed for some other reason, so renaming would change nothing.
        if (newKey == canonical(asset.name, asset.assetType)) return null
        // Another row already holds it (e.g. imported from a broker that gives tickers): renaming would
        // create a duplicate holding, so leave it flagged for a person to merge.
        if (all.any { it.id != asset.id && it.assetType == asset.assetType && canonical(it.name, it.assetType) == newKey }) return null
        return SymbolRepair(asset.id, asset.name, match.symbol, match.basis, match.matchedName)
    }

    private fun canonical(name: String, type: AssetType): String =
        if (type == AssetType.STOCK_IN) SymbolUtils.normaliseIndianSymbol(name) else name.trim().uppercase()

    private fun key(asset: NetWorthAssetEntity) = "${asset.id}|${asset.name.trim().uppercase()}"

    companion object {
        val REPAIRABLE = setOf(AssetType.STOCK_IN, AssetType.STOCK_US, AssetType.CRYPTO)
        const val RETRY_AFTER_MS = 7L * 24 * 60 * 60 * 1000
        private const val MAX_PARALLEL_LOOKUPS = 3
    }
}
