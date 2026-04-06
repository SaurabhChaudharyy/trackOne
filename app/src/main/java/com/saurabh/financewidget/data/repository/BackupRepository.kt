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
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

val BACKUP_IMPORT_MIME_TYPES = arrayOf("application/json", "text/plain", "*/*")

private val ISO_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

data class BackupEnvelope(
    @SerializedName("exported_at") val exportedAt: String = ISO_FORMATTER.format(Instant.now()),
    @SerializedName("watchlist") val watchlist: List<WatchlistBackup> = emptyList(),
    @SerializedName("assets") val assets: List<NetWorthAssetBackup> = emptyList()
)

data class WatchlistBackup(
    @SerializedName("symbol") val symbol: String,
    @SerializedName("name") val displayName: String,
    @SerializedName("position") val position: Int
)

data class NetWorthAssetBackup(
    @SerializedName("name") val name: String,
    @SerializedName("type") val assetType: String,
    @SerializedName("quantity") val quantity: Double,
    @SerializedName("buy_price") val buyPrice: Double,
    @SerializedName("current_value") val currentValue: Double,
    @SerializedName("currency") val currency: String,
    @SerializedName("notes") val notes: String
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

    suspend fun exportWatchlistToUri(uri: Uri): BackupResult = withContext(Dispatchers.IO) {
        try {
            val entities = watchlistDao.getWatchlistSync()
            if (entities.isEmpty()) {
                return@withContext BackupResult.Failure("Nothing to export — your watchlist is empty.")
            }
            val backup = entities.map { WatchlistBackup(it.symbol, it.displayName, it.position) }
            val envelope = BackupEnvelope(watchlist = backup, assets = emptyList())
            writeEnvelope(uri, envelope)
                ?: return@withContext BackupResult.Failure("Could not open file for writing.")
            BackupResult.Success("Exported ${backup.size} watchlist symbol(s).")
        } catch (e: Exception) {
            BackupResult.Failure("Export failed: ${e.message}", e)
        }
    }

    suspend fun importWatchlistFromUri(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        try {
            val envelope = when (val r = readAndValidateEnvelope(uri)) {
                is EnvelopeResult.Err -> return@withContext r.failure
                is EnvelopeResult.Ok  -> r.envelope
            }

            val now = System.currentTimeMillis()
            val entities = envelope.watchlist.mapIndexed { idx, b ->
                WatchlistEntity(
                    symbol = b.symbol.trim().uppercase(),
                    displayName = b.displayName,
                    position = b.position,
                    groupId = 1L,
                    addedAt = now + idx
                )
            }

            watchlistDao.clearWatchlist()
            if (entities.isNotEmpty()) watchlistDao.insertWatchlistItems(entities)

            ImportResult.Success(ImportSummary(watchlistRestored = entities.size, assetsRestored = 0))
        } catch (e: Exception) {
            ImportResult.Failure("Import failed unexpectedly: ${e.message}", e)
        }
    }

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
                    notes = e.notes
                )
            }
            val envelope = BackupEnvelope(watchlist = emptyList(), assets = backup)
            writeEnvelope(uri, envelope)
                ?: return@withContext BackupResult.Failure("Could not open file for writing.")
            BackupResult.Success("Exported ${backup.size} investment(s).")
        } catch (e: Exception) {
            BackupResult.Failure("Export failed: ${e.message}", e)
        }
    }

    suspend fun importAssetsFromUri(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        try {
            val envelope = when (val r = readAndValidateEnvelope(uri)) {
                is EnvelopeResult.Err -> return@withContext r.failure
                is EnvelopeResult.Ok  -> r.envelope
            }

            val invalidTypes = envelope.assets
                .filter { runCatching { AssetType.valueOf(it.assetType) }.isFailure }
                .map { it.assetType }
            if (invalidTypes.isNotEmpty()) {
                return@withContext ImportResult.Failure(
                    "Backup contains unknown asset type(s): ${invalidTypes.joinToString()}. " +
                    "The file may be corrupt or from an incompatible version."
                )
            }

            val now = System.currentTimeMillis()
            val entities = envelope.assets.map { b ->
                NetWorthAssetEntity(
                    id = 0,
                    name = b.name,
                    assetType = AssetType.valueOf(b.assetType),
                    quantity = b.quantity,
                    buyPrice = b.buyPrice,
                    currentValue = b.currentValue,
                    currency = b.currency,
                    notes = b.notes,
                    addedAt = now,
                    updatedAt = now
                )
            }

            netWorthDao.deleteAllAssets()
            if (entities.isNotEmpty()) netWorthDao.insertAssets(entities)

            ImportResult.Success(ImportSummary(watchlistRestored = 0, assetsRestored = entities.size))
        } catch (e: Exception) {
            ImportResult.Failure("Import failed unexpectedly: ${e.message}", e)
        }
    }

    private fun writeEnvelope(uri: Uri, envelope: BackupEnvelope): Unit? {
        return context.contentResolver.openOutputStream(uri)?.use { stream ->
            stream.bufferedWriter().use { it.write(gson.toJson(envelope)) }
        }
    }

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

        return EnvelopeResult.Ok(envelope)
    }
}
