package com.saurabh.financewidget.data.database

import androidx.lifecycle.LiveData
import androidx.room.*

@Dao
interface StockDao {

    @Query("SELECT * FROM stocks WHERE symbol IN (SELECT symbol FROM watchlist) ORDER BY (SELECT position FROM watchlist WHERE watchlist.symbol = stocks.symbol)")
    fun getWatchlistStocks(): LiveData<List<StockEntity>>

    @Query("SELECT * FROM stocks WHERE symbol IN (SELECT symbol FROM watchlist) ORDER BY (SELECT position FROM watchlist WHERE watchlist.symbol = stocks.symbol)")
    suspend fun getWatchlistStocksSync(): List<StockEntity>

    @Query("SELECT * FROM stocks WHERE symbol = :symbol")
    suspend fun getStock(symbol: String): StockEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStock(stock: StockEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStocks(stocks: List<StockEntity>)

    @Update
    suspend fun updateStock(stock: StockEntity)

    @Query("DELETE FROM stocks WHERE symbol = :symbol")
    suspend fun deleteStock(symbol: String)

    @Query("SELECT * FROM stocks WHERE symbol NOT IN (SELECT symbol FROM watchlist)")
    suspend fun getNonWatchlistStocks(): List<StockEntity>
}

@Dao
interface WatchlistDao {

    @Query("SELECT * FROM watchlist ORDER BY position ASC")
    fun getWatchlist(): LiveData<List<WatchlistEntity>>

    @Query("SELECT * FROM watchlist ORDER BY position ASC")
    suspend fun getWatchlistSync(): List<WatchlistEntity>

    @Query("SELECT COUNT(*) FROM watchlist WHERE symbol = :symbol")
    suspend fun isInWatchlist(symbol: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addToWatchlist(item: WatchlistEntity)

    @Query("DELETE FROM watchlist WHERE symbol = :symbol")
    suspend fun removeFromWatchlist(symbol: String)

    @Query("UPDATE watchlist SET position = :position WHERE symbol = :symbol")
    suspend fun updatePosition(symbol: String, position: Int)

    @Query("SELECT MAX(position) FROM watchlist")
    suspend fun getMaxPosition(): Int?

    @Query("DELETE FROM watchlist")
    suspend fun clearWatchlist()
}

@Dao
interface PriceHistoryDao {

    @Query("SELECT * FROM price_history WHERE symbol = :symbol AND resolution = :resolution ORDER BY timestamp ASC")
    suspend fun getPriceHistory(symbol: String, resolution: String): List<PriceHistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPriceHistory(history: List<PriceHistoryEntity>)

    @Query("DELETE FROM price_history WHERE symbol = :symbol AND resolution = :resolution")
    suspend fun deletePriceHistory(symbol: String, resolution: String)

    @Query("DELETE FROM price_history WHERE timestamp < :cutoffTime")
    suspend fun deleteOldHistory(cutoffTime: Long)
}
