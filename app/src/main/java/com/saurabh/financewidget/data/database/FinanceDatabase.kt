package com.saurabh.financewidget.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS networth_assets (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT NOT NULL,
                assetType TEXT NOT NULL,
                quantity REAL NOT NULL,
                buyPrice REAL NOT NULL,
                currentValue REAL NOT NULL,
                currency TEXT NOT NULL,
                notes TEXT NOT NULL,
                addedAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL
            )
        """.trimIndent())
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS watchlist_groups (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT NOT NULL,
                position INTEGER NOT NULL,
                createdAt INTEGER NOT NULL
            )
        """.trimIndent())
        // Insert a default group to satisfy the foreign key or logical default (groupId = 1)
        db.execSQL("""
            INSERT OR IGNORE INTO watchlist_groups (id, name, position, createdAt) 
            VALUES (1, 'My Watchlist', 0, ${System.currentTimeMillis()})
        """.trimIndent())
    }
}

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
