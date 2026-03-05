package com.saurabh.financewidget.data.repository

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import com.saurabh.financewidget.data.database.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

const val BACKUP_SCHEMA_VERSION = 1
val BACKUP_IMPORT_MIME_TYPES = arrayOf("application/json", "text/plain", "*/*")


/**
 * Root JSON object written/read for every backup.
 * [schemaVersion] lets us detect incompatible files in future migrations.
 */
data class BackupEnvelope(
    @SerializedName("schema_version") val schemaVersion: Int = BACKUP_SCHEMA_VERSION,
    @SerializedName("exported_at_ms") val exportedAtMs: Long = System.currentTimeMillis(),
    @SerializedName("app_package") val appPackage: String = "com.saurabh.financewidget",
    @SerializedName("watchlist") val watchlist: List<WatchlistBackup> = emptyList(),
    @SerializedName("networth_assets") val networthAssets: List<NetWorthAssetBackup> = emptyList()
)

data class WatchlistBackup(
    @SerializedName("symbol") val symbol: String,
    @SerializedName("display_name") val displayName: String,
    @SerializedName("position") val position: Int,
    @SerializedName("added_at_ms") val addedAt: Long
)

data class NetWorthAssetBackup(
    @SerializedName("name") val name: String,
    @SerializedName("asset_type") val assetType: String,
    @SerializedName("quantity") val quantity: Double,
    @SerializedName("buy_price") val buyPrice: Double,
    @SerializedName("current_value") val currentValue: Double,
    @SerializedName("currency") val currency: String,
    @SerializedName("notes") val notes: String,
    @SerializedName("added_at_ms") val addedAt: Long,
    @SerializedName("updated_at_ms") val updatedAt: Long
)


sealed class BackupResult {
    data class Success(val message: String) : BackupResult()
    data class Failure(val reason: String, val cause: Throwable? = null) : BackupResult()
}

data class ImportSummary(
    val watchlistRestored: Int,
    val assetsRestored: Int
)

sealed class ImportResult {
    data class Success(val summary: ImportSummary) : ImportResult()
    data class Failure(val reason: String, val cause: Throwable? = null) : ImportResult()
}

@Singleton
class BackupRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val watchlistDao: WatchlistDao,
    private val netWorthDao: NetWorthDao
) {
    companion object {
        val IMPORT_MIME_TYPES = BACKUP_IMPORT_MIME_TYPES
    }

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    suspend fun exportToUri(uri: Uri): BackupResult = withContext(Dispatchers.IO) {
        try {
            val watchlistEntities = watchlistDao.getWatchlistSync()
            val assetEntities = netWorthDao.getAllAssetsSync()

            if (watchlistEntities.isEmpty() && assetEntities.isEmpty()) {
                return@withContext BackupResult.Failure(
                    "Nothing to export — your watchlist and net worth are both empty."
                )
            }

            val watchlistBackup = watchlistEntities.map { e ->
                WatchlistBackup(e.symbol, e.displayName, e.position, e.addedAt)
            }
            val assetBackup = assetEntities.map { e ->
                NetWorthAssetBackup(
                    name = e.name,
                    assetType = e.assetType.name,
                    quantity = e.quantity,
                    buyPrice = e.buyPrice,
                    currentValue = e.currentValue,
                    currency = e.currency,
                    notes = e.notes,
                    addedAt = e.addedAt,
                    updatedAt = e.updatedAt
                )
            }

            val envelope = BackupEnvelope(
                watchlist = watchlistBackup,
                networthAssets = assetBackup
            )

            context.contentResolver.openOutputStream(uri)?.use { stream ->
                stream.bufferedWriter().use { writer ->
                    writer.write(gson.toJson(envelope))
                }
            } ?: return@withContext BackupResult.Failure("Could not open file for writing.")

            val total = watchlistBackup.size + assetBackup.size
            BackupResult.Success(
                "Exported $total item(s): ${watchlistBackup.size} watchlist, ${assetBackup.size} net worth."
            )
        } catch (e: Exception) {
            BackupResult.Failure("Export failed: ${e.message}", e)
        }
    }

    /**
     * Reads a backup JSON from [uri], validates it, then atomically replaces
     * all local data (watchlist + net worth assets) with the restored rows.
     * The stocks cache table is intentionally not restored — prices re-fetch live.
     */
    suspend fun importFromUri(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        try {
            val json = context.contentResolver.openInputStream(uri)
                ?.bufferedReader()
                ?.use { it.readText() }
                ?: return@withContext ImportResult.Failure("Could not open the selected file.")

            if (json.isBlank()) {
                return@withContext ImportResult.Failure("The selected file is empty.")
            }

            val envelope = try {
                gson.fromJson(json, BackupEnvelope::class.java)
            } catch (e: Exception) {
                return@withContext ImportResult.Failure(
                    "Invalid backup file — the JSON could not be parsed. Make sure you selected a trackOne backup file.",
                    e
                )
            }

            if (envelope == null) {
                return@withContext ImportResult.Failure("Backup file appears to be corrupt (null envelope).")
            }

            if (envelope.appPackage.isNotBlank() &&
                envelope.appPackage != "com.saurabh.financewidget") {
                return@withContext ImportResult.Failure(
                    "This backup was created by a different app (${envelope.appPackage}). " +
                    "Only trackOne backup files can be imported."
                )
            }

            if (envelope.schemaVersion > BACKUP_SCHEMA_VERSION) {
                return@withContext ImportResult.Failure(
                    "This backup was created with a newer version of trackOne " +
                    "(schema v${envelope.schemaVersion}). Please update the app and try again."
                )
            }

            val invalidTypes = envelope.networthAssets
                .filter { runCatching { AssetType.valueOf(it.assetType) }.isFailure }
                .map { it.assetType }
            if (invalidTypes.isNotEmpty()) {
                return@withContext ImportResult.Failure(
                    "Backup contains unknown asset type(s): ${invalidTypes.joinToString()}. " +
                    "The file may be corrupt or from an incompatible version."
                )
            }

            val watchlistEntities = envelope.watchlist.map { b ->
                WatchlistEntity(
                    symbol = b.symbol.trim().uppercase(),
                    displayName = b.displayName,
                    position = b.position,
                    addedAt = b.addedAt
                )
            }
            val assetEntities = envelope.networthAssets.map { b ->
                NetWorthAssetEntity(
                    id = 0,
                    name = b.name,
                    assetType = AssetType.valueOf(b.assetType),
                    quantity = b.quantity,
                    buyPrice = b.buyPrice,
                    currentValue = b.currentValue,
                    currency = b.currency,
                    notes = b.notes,
                    addedAt = b.addedAt,
                    updatedAt = b.updatedAt
                )
            }

            watchlistDao.clearWatchlist()
            netWorthDao.deleteAllAssets()

            if (watchlistEntities.isNotEmpty()) {
                watchlistDao.insertWatchlistItems(watchlistEntities)
            }
            if (assetEntities.isNotEmpty()) {
                netWorthDao.insertAssets(assetEntities)
            }

            ImportResult.Success(
                ImportSummary(
                    watchlistRestored = watchlistEntities.size,
                    assetsRestored = assetEntities.size
                )
            )
        } catch (e: Exception) {
            ImportResult.Failure("Import failed unexpectedly: ${e.message}", e)
        }
    }
}
