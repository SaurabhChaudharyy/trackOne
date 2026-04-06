package com.saurabh.financewidget.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Recreate watchlist table with composite PK (symbol, groupId)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS watchlist_new (
                symbol      TEXT    NOT NULL,
                displayName TEXT    NOT NULL,
                position    INTEGER NOT NULL,
                groupId     INTEGER NOT NULL DEFAULT 1,
                addedAt     INTEGER NOT NULL,
                PRIMARY KEY (symbol, groupId)
            )
        """.trimIndent())
        // Migrate existing rows — groupId defaults to 1 so all go to "My Watchlist"
        db.execSQL("""
            INSERT OR IGNORE INTO watchlist_new (symbol, displayName, position, groupId, addedAt)
            SELECT symbol, displayName, position, groupId, addedAt FROM watchlist
        """.trimIndent())
        db.execSQL("DROP TABLE watchlist")
        db.execSQL("ALTER TABLE watchlist_new RENAME TO watchlist")
    }
}

@TypeConverters(Converters::class)
@Database(
    entities = [StockEntity::class, WatchlistGroupEntity::class, WatchlistEntity::class,
                PriceHistoryEntity::class, NetWorthAssetEntity::class],
    version = 4,
    exportSchema = false
)
abstract class FinanceDatabase : RoomDatabase() {
    abstract fun stockDao(): StockDao
    abstract fun watchlistGroupDao(): WatchlistGroupDao
    abstract fun watchlistDao(): WatchlistDao
    abstract fun priceHistoryDao(): PriceHistoryDao
    abstract fun netWorthDao(): NetWorthDao
}
