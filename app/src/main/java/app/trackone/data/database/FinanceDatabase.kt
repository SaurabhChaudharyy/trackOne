package app.trackone.data.database

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
        // Physically add the groupId column to the existing `watchlist` table so that
        // MIGRATION_3_4's `SELECT ... groupId FROM watchlist` has a real column to read.
        // Without this, upgrading a real on-device DB from v2/v3 to v4 throws
        // "no such column: groupId" and the app fails to open its database.
        db.execSQL("""
            ALTER TABLE watchlist ADD COLUMN groupId INTEGER NOT NULL DEFAULT 1
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

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE networth_assets ADD COLUMN isin TEXT")
        db.execSQL("ALTER TABLE networth_assets ADD COLUMN brokerSource TEXT")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS networth_transactions (
                id              INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                assetId         INTEGER NOT NULL,
                symbol          TEXT    NOT NULL,
                assetType       TEXT    NOT NULL,
                transactionType TEXT    NOT NULL,
                quantity        REAL    NOT NULL,
                price           REAL    NOT NULL,
                currency        TEXT    NOT NULL,
                transactionDate INTEGER NOT NULL,
                isin            TEXT,
                brokerSource    TEXT,
                notes           TEXT    NOT NULL,
                createdAt       INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_networth_transactions_assetId ON networth_transactions(assetId)")
    }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS expense_transactions (
                id          INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                date        INTEGER NOT NULL,
                amount      REAL    NOT NULL,
                description TEXT    NOT NULL,
                category    TEXT    NOT NULL,
                account     TEXT    NOT NULL,
                currency    TEXT    NOT NULL,
                createdAt   INTEGER NOT NULL
            )
        """.trimIndent())
    }
}

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // The latest trading session Yahoo reports for each saved quote; 0 until the next fetch fills it.
        db.execSQL("ALTER TABLE stocks ADD COLUMN sessionPreStart INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE stocks ADD COLUMN sessionStart INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE stocks ADD COLUMN sessionEnd INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE stocks ADD COLUMN sessionPostEnd INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE stocks ADD COLUMN sessionGmtOffset INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Yesterday's close per unit; 0 until the next live price fills it.
        db.execSQL("ALTER TABLE networth_assets ADD COLUMN previousClosePrice REAL NOT NULL DEFAULT 0")
    }
}

@TypeConverters(Converters::class)
@Database(
    entities = [StockEntity::class, WatchlistGroupEntity::class, WatchlistEntity::class,
                PriceHistoryEntity::class, NetWorthAssetEntity::class, NetWorthTransactionEntity::class,
                ExpenseTransactionEntity::class],
    version = 8,
    exportSchema = false
)
abstract class FinanceDatabase : RoomDatabase() {
    abstract fun stockDao(): StockDao
    abstract fun watchlistGroupDao(): WatchlistGroupDao
    abstract fun watchlistDao(): WatchlistDao
    abstract fun priceHistoryDao(): PriceHistoryDao
    abstract fun netWorthDao(): NetWorthDao
    abstract fun netWorthTransactionDao(): NetWorthTransactionDao
    abstract fun expenseDao(): ExpenseDao
}
