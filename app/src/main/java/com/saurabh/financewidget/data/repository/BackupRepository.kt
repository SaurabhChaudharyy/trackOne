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

/** Internal sealed type used by [BackupRepository.readAndValidateEnvelope]. */
private sealed class EnvelopeResult {
    data class Ok(val envelope: BackupEnvelope) : EnvelopeResult()
    data class Err(val failure: ImportResult.Failure) : EnvelopeResult()
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

    // ──────────────────────────────────────────────
    // Watchlist-only export / import
    // ──────────────────────────────────────────────

    /** Exports only the watchlist symbols to [uri] as a trackOne JSON file. */
    suspend fun exportWatchlistToUri(uri: Uri): BackupResult = withContext(Dispatchers.IO) {
        try {
            val entities = watchlistDao.getWatchlistSync()
            if (entities.isEmpty()) {
                return@withContext BackupResult.Failure("Nothing to export — your watchlist is empty.")
            }
            val backup = entities.map { WatchlistBackup(it.symbol, it.displayName, it.position, it.addedAt) }
            val envelope = BackupEnvelope(watchlist = backup, networthAssets = emptyList())
            writeEnvelope(uri, envelope)
                ?: return@withContext BackupResult.Failure("Could not open file for writing.")
            BackupResult.Success("Exported ${backup.size} watchlist symbol(s).")
        } catch (e: Exception) {
            BackupResult.Failure("Export failed: ${e.message}", e)
        }
    }

    /**
     * Imports watchlist from [uri]. Clears the watchlist table and restores
     * only the watchlist section — net worth assets are untouched.
     */
    suspend fun importWatchlistFromUri(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        try {
            val envelope = when (val r = readAndValidateEnvelope(uri)) {
                is EnvelopeResult.Err -> return@withContext r.failure
                is EnvelopeResult.Ok  -> r.envelope
            }

            val entities = envelope.watchlist.map { b ->
                WatchlistEntity(
                    symbol = b.symbol.trim().uppercase(),
                    displayName = b.displayName,
                    position = b.position,
                    addedAt = b.addedAt
                )
            }

            watchlistDao.clearWatchlist()
            if (entities.isNotEmpty()) watchlistDao.insertWatchlistItems(entities)

            ImportResult.Success(ImportSummary(watchlistRestored = entities.size, assetsRestored = 0))
        } catch (e: Exception) {
            ImportResult.Failure("Import failed unexpectedly: ${e.message}", e)
        }
    }

    // ──────────────────────────────────────────────
    // Stocks & Investments (net worth assets) export / import
    // ──────────────────────────────────────────────

    /** Exports only the net worth assets to [uri] as a trackOne JSON file. */
    suspend fun exportAssetsToUri(uri: Uri): BackupResult = withContext(Dispatchers.IO) {
        try {
            val entities = netWorthDao.getAllAssetsSync()
            if (entities.isEmpty()) {
                return@withContext BackupResult.Failure("Nothing to export — your investments list is empty.")
            }
            val backup = entities.map { e ->
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
            val envelope = BackupEnvelope(watchlist = emptyList(), networthAssets = backup)
            writeEnvelope(uri, envelope)
                ?: return@withContext BackupResult.Failure("Could not open file for writing.")
            BackupResult.Success("Exported ${backup.size} investment(s).")
        } catch (e: Exception) {
            BackupResult.Failure("Export failed: ${e.message}", e)
        }
    }

    /**
     * Imports net worth assets from [uri]. Clears the networth_assets table and
     * restores only the assets section — watchlist is untouched.
     */
    suspend fun importAssetsFromUri(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        try {
            val envelope = when (val r = readAndValidateEnvelope(uri)) {
                is EnvelopeResult.Err -> return@withContext r.failure
                is EnvelopeResult.Ok  -> r.envelope
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

            val entities = envelope.networthAssets.map { b ->
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

            netWorthDao.deleteAllAssets()
            if (entities.isNotEmpty()) netWorthDao.insertAssets(entities)

            ImportResult.Success(ImportSummary(watchlistRestored = 0, assetsRestored = entities.size))
        } catch (e: Exception) {
            ImportResult.Failure("Import failed unexpectedly: ${e.message}", e)
        }
    }

    // ──────────────────────────────────────────────
    // Private helpers
    // ──────────────────────────────────────────────

    /** Writes [envelope] as pretty-printed JSON to [uri]. Returns null if the stream couldn't be opened. */
    private fun writeEnvelope(uri: Uri, envelope: BackupEnvelope): Unit? {
        return context.contentResolver.openOutputStream(uri)?.use { stream ->
            stream.bufferedWriter().use { it.write(gson.toJson(envelope)) }
        }
    }

    /**
     * Opens [uri], parses the JSON, and validates app package + schema version.
     * Returns [EnvelopeResult.Ok] with the parsed envelope, or [EnvelopeResult.Err] on any failure.
     */
    private fun readAndValidateEnvelope(uri: Uri): EnvelopeResult {
        val json = context.contentResolver.openInputStream(uri)
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: return EnvelopeResult.Err(ImportResult.Failure("Could not open the selected file."))

        if (json.isBlank()) {
            return EnvelopeResult.Err(ImportResult.Failure("The selected file is empty."))
        }

        val envelope = try {
            gson.fromJson(json, BackupEnvelope::class.java)
        } catch (e: Exception) {
            return EnvelopeResult.Err(
                ImportResult.Failure(
                    "Invalid backup file — the JSON could not be parsed. Make sure you selected a trackOne backup file.",
                    e
                )
            )
        } ?: return EnvelopeResult.Err(
            ImportResult.Failure("Backup file appears to be corrupt (null envelope).")
        )

        if (envelope.appPackage.isNotBlank() && envelope.appPackage != "com.saurabh.financewidget") {
            return EnvelopeResult.Err(
                ImportResult.Failure(
                    "This backup was created by a different app (${envelope.appPackage}). " +
                    "Only trackOne backup files can be imported."
                )
            )
        }

        if (envelope.schemaVersion > BACKUP_SCHEMA_VERSION) {
            return EnvelopeResult.Err(
                ImportResult.Failure(
                    "This backup was created with a newer version of trackOne " +
                    "(schema v${envelope.schemaVersion}). Please update the app and try again."
                )
            )
        }

        return EnvelopeResult.Ok(envelope)
    }
}
