package com.saurabh.financewidget.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Entity(tableName = "stocks")
@Parcelize
data class StockEntity(
    @PrimaryKey
    val symbol: String,
    val companyName: String,
    val currentPrice: Double,
    val change: Double,
    val changePercent: Double,
    val highPrice: Double,
    val lowPrice: Double,
    val openPrice: Double,
    val previousClose: Double,
    val marketCap: Double = 0.0,
    val industry: String = "",
    val exchange: String = "",
    val currency: String = "USD",
    val logo: String = "",
    val website: String = "",
    val lastUpdated: Long = System.currentTimeMillis(),
    val isInWatchlist: Boolean = false
) : Parcelable {
    val isPositive: Boolean get() = change >= 0
    val isStale: Boolean get() = System.currentTimeMillis() - lastUpdated > 15 * 60 * 1000
}

@Entity(tableName = "watchlist")
data class WatchlistEntity(
    @PrimaryKey
    val symbol: String,
    val displayName: String,
    val position: Int,
    val addedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "price_history")
data class PriceHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val symbol: String,
    val timestamp: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long,
    val resolution: String
)
