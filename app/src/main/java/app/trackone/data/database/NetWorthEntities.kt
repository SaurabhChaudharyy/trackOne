package app.trackone.data.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class AssetType {
    STOCK_IN,   
    STOCK_US,   
    MF,         
    GOLD,       
    SILVER,     
    CRYPTO,     
    CASH,       
    BANK        
}

@Entity(tableName = "networth_assets")
data class NetWorthAssetEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val assetType: AssetType,
    val quantity: Double = 1.0,
    val buyPrice: Double = 0.0,
    val currentValue: Double,
    val currency: String = "INR",
    val notes: String = "",
    val addedAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** ISIN, when known (mostly Indian broker imports). Null for manually-added assets. */
    val isin: String? = null,
    /** Which broker this holding was imported from, e.g. "Zerodha / Groww". Null if added manually. */
    val brokerSource: String? = null
)

enum class TransactionType { BUY, SELL }

/**
 * One historical purchase (or sale) lot for a holding — separate from the aggregate
 * position in [NetWorthAssetEntity] so a holding's buy history can be tracked over time.
 *
 * Caveat: a broker's "holdings statement" (what BrokerCsvRepository imports today) only
 * reports the current aggregate quantity/avg-price, not individual trade dates — so each
 * CSV import records one synthetic lot dated at import time, not the real historical trade
 * dates. Real per-trade history requires importing the broker's tradebook/transaction
 * statement instead, which isn't wired up yet.
 */
@Entity(tableName = "networth_transactions", indices = [Index(value = ["assetId"])])
data class NetWorthTransactionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** FK to networth_assets.id. Not enforced at the DB level so a transaction survives
     *  its asset being re-imported (assets are matched/updated by name+type, not by id). */
    val assetId: Long,
    val symbol: String,
    val assetType: AssetType,
    val transactionType: TransactionType = TransactionType.BUY,
    val quantity: Double,
    val price: Double,
    val currency: String = "INR",
    /** When the trade actually happened, if known; otherwise the import timestamp. */
    val transactionDate: Long = System.currentTimeMillis(),
    val isin: String? = null,
    val brokerSource: String? = null,
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis()
)
