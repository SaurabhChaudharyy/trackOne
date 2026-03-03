package com.saurabh.financewidget.data.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [StockEntity::class, WatchlistEntity::class, PriceHistoryEntity::class],
    version = 1,
    exportSchema = false
)
abstract class FinanceDatabase : RoomDatabase() {
    abstract fun stockDao(): StockDao
    abstract fun watchlistDao(): WatchlistDao
    abstract fun priceHistoryDao(): PriceHistoryDao
}
