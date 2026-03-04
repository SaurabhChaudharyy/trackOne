package com.saurabh.financewidget.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@TypeConverters(Converters::class)
@Database(
    entities = [StockEntity::class, WatchlistEntity::class, PriceHistoryEntity::class,
                NetWorthAssetEntity::class],
    version = 2,
    exportSchema = false
)
abstract class FinanceDatabase : RoomDatabase() {
    abstract fun stockDao(): StockDao
    abstract fun watchlistDao(): WatchlistDao
    abstract fun priceHistoryDao(): PriceHistoryDao
    abstract fun netWorthDao(): NetWorthDao
}
