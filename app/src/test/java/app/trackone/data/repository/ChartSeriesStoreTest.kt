package app.trackone.data.repository

import app.trackone.utils.ChartRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The price series behind the Home chart, kept on disk so a cold start does not download a month of
 * closes for every holding again. A saved series has to come back exactly, and anything wrong with a
 * file (damaged, cut short by the process being killed mid-write) has to read as "not saved", never as
 * a crash or a half-drawn chart.
 */
class ChartSeriesStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var dir: File
    private lateinit var store: ChartSeriesStore

    @Before
    fun setUp() {
        dir = File(tmp.root, "series")
        store = ChartSeriesStore(dir)
    }

    private fun series(at: Long = 1_000L, currency: String = "INR", vararg points: Pair<Long, Double>) =
        ChartSeriesStore.Stored(at, currency, points.toList().ifEmpty { listOf(20_000L to 100.5, 20_001L to 101.25) })

    @Test
    fun `a saved series comes back exactly`() {
        val saved = series(at = 1_790_000_123_456L, currency = "USD", 20_000L to 187.123456789, 20_001L to 0.0001, 20_002L to 1.0E7)

        store.save("AAPL", ChartRange.MONTH, saved)
        val loaded = store.load("AAPL", ChartRange.MONTH)!!

        assertEquals(saved.fetchedAtMs, loaded.fetchedAtMs)
        assertEquals("USD", loaded.currency)
        assertEquals(saved.points, loaded.points)
    }

    @Test
    fun `nothing saved reads as null`() {
        assertNull(store.load("AAPL", ChartRange.MONTH))
    }

    @Test
    fun `a series is kept per symbol and per range`() {
        store.save("AAPL", ChartRange.MONTH, series(currency = "USD"))
        store.save("AAPL", ChartRange.YEAR, series(currency = "EUR"))

        assertEquals("USD", store.load("AAPL", ChartRange.MONTH)!!.currency)
        assertEquals("EUR", store.load("AAPL", ChartRange.YEAR)!!.currency)
        assertNull(store.load("MSFT", ChartRange.MONTH))
    }

    @Test
    fun `symbols that look alike are different series`() {
        // Yahoo symbols carry ^ = . - and spaces (a broker name saved as a ticker): they must not share a file.
        val symbols = listOf("BRK-B", "BRK B", "BRK_B", "^NSEI", "_NSEI", "GC=F", "GC_F", "HDFC BANK LTD.NS")
        symbols.forEachIndexed { i, s -> store.save(s, ChartRange.MONTH, series(currency = "C$i")) }

        symbols.forEachIndexed { i, s -> assertEquals(s, "C$i", store.load(s, ChartRange.MONTH)?.currency) }
    }

    @Test
    fun `saving again replaces the old series`() {
        store.save("AAPL", ChartRange.MONTH, series(at = 1L, currency = "OLD"))
        store.save("AAPL", ChartRange.MONTH, series(at = 2L, currency = "NEW"))

        val loaded = store.load("AAPL", ChartRange.MONTH)!!
        assertEquals("NEW", loaded.currency)
        assertEquals(2L, loaded.fetchedAtMs)
    }

    private fun fileFor(symbol: String, range: ChartRange): File {
        store.save(symbol, range, series())
        return dir.listFiles()!!.single()
    }

    @Test
    fun `a damaged file reads as null instead of throwing`() {
        fileFor("AAPL", ChartRange.MONTH).writeText("this is not a series")

        assertNull(store.load("AAPL", ChartRange.MONTH))
    }

    @Test
    fun `a file cut short by a killed process reads as null`() {
        val file = fileFor("AAPL", ChartRange.MONTH)
        file.writeText(file.readText().trimEnd().dropLast(10))    // the last row is left as "20", no comma, no close

        assertNull(store.load("AAPL", ChartRange.MONTH))
    }

    @Test
    fun `a file with a header and no points reads as null`() {
        val file = fileFor("AAPL", ChartRange.MONTH)
        file.writeText(file.readLines().first() + "\n")

        assertNull(store.load("AAPL", ChartRange.MONTH))
    }

    @Test
    fun `an empty file reads as null`() {
        fileFor("AAPL", ChartRange.MONTH).writeText("")

        assertNull(store.load("AAPL", ChartRange.MONTH))
    }

    @Test
    fun `a file written by a different format version is ignored`() {
        val file = fileFor("AAPL", ChartRange.MONTH)
        file.writeText(file.readText().replaceFirst("v1", "v9"))

        assertNull(store.load("AAPL", ChartRange.MONTH))
    }

    @Test
    fun `saving leaves no temporary files behind`() {
        store.save("AAPL", ChartRange.MONTH, series())
        store.save("AAPL", ChartRange.MONTH, series())

        assertEquals(1, dir.listFiles()!!.size)
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test
    fun `pruning removes series saved before the cutoff and keeps newer ones`() {
        store.save("OLD", ChartRange.MONTH, series(at = 1_000L))
        store.save("NEW", ChartRange.MONTH, series(at = 9_000L))

        store.prune(olderThanMs = 5_000L)

        assertNull(store.load("OLD", ChartRange.MONTH))
        assertNotEquals(null, store.load("NEW", ChartRange.MONTH))
    }

    @Test
    fun `pruning a store that was never written to does nothing`() {
        store.prune(olderThanMs = 5_000L)                         // no directory yet

        assertNull(store.load("AAPL", ChartRange.MONTH))
    }
}
