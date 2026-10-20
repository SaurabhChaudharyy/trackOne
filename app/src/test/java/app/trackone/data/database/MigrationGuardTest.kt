package app.trackone.data.database

import app.trackone.palette.ColorResources
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The database is built with `fallbackToDestructiveMigration()`, so a schema change without a matching
 * migration does not crash: it silently deletes the user's watchlist and net worth. These checks read
 * the sources to make sure a column added to StockEntity has a migration that adds it.
 */
class MigrationGuardTest {

    private val java = File(ColorResources.mainDir, "java/app/trackone")
    private val database = File(java, "data/database/FinanceDatabase.kt").readText()
    private val entities = File(java, "data/database/Entities.kt").readText()
    private val module = File(java, "di/AppModule.kt").readText()

    /** StockEntity's columns as of schema version 6, the last one before session times were added. */
    private val columnsAtV6 = setOf(
        "symbol", "companyName", "currentPrice", "change", "changePercent", "highPrice", "lowPrice", "openPrice",
        "previousClose", "marketCap", "industry", "exchange", "currency", "logo", "website", "lastUpdated", "isInWatchlist"
    )

    private fun stockColumns(): Set<String> {
        val body = entities.substringAfter("data class StockEntity(").substringBefore(") : Parcelable")
        return Regex("""val\s+(\w+)\s*:""").findAll(body).map { it.groupValues[1] }.toSet()
    }

    private fun migration(name: String) =
        Regex("""val $name = object : Migration\(\d+, \d+\) \{(.*?)\n\}""", RegexOption.DOT_MATCHES_ALL)
            .find(database)?.groupValues?.get(1)

    @Test
    fun `the database version matches the newest migration`() {
        val version = Regex("""version\s*=\s*(\d+)""").find(database)!!.groupValues[1].toInt()
        val newest = Regex("""MIGRATION_\d+_(\d+)""").findAll(database).maxOf { it.groupValues[1].toInt() }

        assertTrue("version = $version but the newest migration ends at $newest", version == newest)
    }

    @Test
    fun `every migration is registered with the database builder`() {
        val defined = Regex("""val (MIGRATION_\d+_\d+) = object""").findAll(database).map { it.groupValues[1] }.toList()
        val registered = module.substringAfter("addMigrations(").substringBefore(")")
        val missing = defined.filterNot { registered.contains(it) }

        assertTrue("Defined but not passed to addMigrations: $missing", missing.isEmpty())
    }

    @Test
    fun `every column added to a saved quote since version 6 is added by a migration`() {
        val added = stockColumns() - columnsAtV6
        val sql = migration("MIGRATION_6_7") ?: ""

        val missing = added.filterNot { Regex("""ALTER TABLE stocks ADD COLUMN $it\s+INTEGER NOT NULL DEFAULT 0""").containsMatchIn(sql) }
        assertTrue("StockEntity columns with no ADD COLUMN in MIGRATION_6_7: $missing", missing.isEmpty() && added.isNotEmpty())
    }
}
