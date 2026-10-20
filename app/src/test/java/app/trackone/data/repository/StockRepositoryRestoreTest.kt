package app.trackone.data.repository

import app.trackone.data.api.RateLimitBackoff
import app.trackone.data.api.YahooFinanceApiService
import app.trackone.data.database.PriceHistoryDao
import app.trackone.data.database.StockDao
import app.trackone.data.database.WatchlistDao
import app.trackone.data.database.WatchlistEntity
import app.trackone.data.database.WatchlistGroupDao
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * UNDO after removing a stock must put it back where it was. addToWatchlist always appends, so undo
 * needs its own path that makes room at the old index (v1.4.5 always dropped the stock at the bottom).
 */
class StockRepositoryRestoreTest {

    private val group = 1L
    private lateinit var watchlistDao: WatchlistDao
    private lateinit var repo: StockRepository

    @Before
    fun setUp() {
        watchlistDao = mockk(relaxed = true)
        repo = StockRepository(
            mockk<YahooFinanceApiService>(),
            mockk<StockDao>(relaxed = true),
            mockk<WatchlistGroupDao>(relaxed = true),
            watchlistDao,
            mockk<PriceHistoryDao>(relaxed = true),
            RateLimitBackoff { 0L }
        )
    }

    /** What the DAO holds after a removal: [symbols] in order, at the stored [positions]. */
    private fun holding(symbols: List<String>, positions: List<Int> = symbols.indices.toList()) {
        coEvery { watchlistDao.getWatchlistSyncByGroup(group) } returns
            symbols.mapIndexed { i, s -> WatchlistEntity(s, s, positions[i], group) }
    }

    @Test
    fun `a stock removed from the middle comes back to the middle`() = runTest {
        holding(listOf("A", "B", "D"))                 // C was at index 2

        repo.restoreToWatchlist("C", "Company C", group, index = 2)

        coVerify { watchlistDao.addToWatchlist(match { it.symbol == "C" && it.position == 2 && it.groupId == group }) }
        coVerify { watchlistDao.updatePosition("D", group, 3) }   // D shifts down to make room
        coVerify { watchlistDao.updatePosition("A", group, 0) }
        coVerify { watchlistDao.updatePosition("B", group, 1) }
    }

    @Test
    fun `a stock removed from the top comes back to the top`() = runTest {
        holding(listOf("B", "C"))

        repo.restoreToWatchlist("A", "Company A", group, index = 0)

        coVerify { watchlistDao.addToWatchlist(match { it.symbol == "A" && it.position == 0 }) }
        coVerify { watchlistDao.updatePosition("B", group, 1) }
        coVerify { watchlistDao.updatePosition("C", group, 2) }
    }

    @Test
    fun `an index past the end appends`() = runTest {
        holding(listOf("A", "B"))                      // the list shrank again before the undo

        repo.restoreToWatchlist("Z", "Company Z", group, index = 7)

        coVerify { watchlistDao.addToWatchlist(match { it.symbol == "Z" && it.position == 2 }) }
    }

    @Test
    fun `stored positions with gaps are renumbered rather than trusted`() = runTest {
        holding(listOf("A", "B", "D"), positions = listOf(0, 1, 3))   // earlier removals left a gap

        repo.restoreToWatchlist("C", "Company C", group, index = 2)

        coVerify { watchlistDao.addToWatchlist(match { it.symbol == "C" && it.position == 2 }) }
        coVerify { watchlistDao.updatePosition("D", group, 3) }
    }

    @Test
    fun `a restored stock keeps the date it was first added`() = runTest {
        holding(listOf("A", "B"))

        repo.restoreToWatchlist("C", "Company C", group, index = 1, addedAt = 1_700_000_000_000L)

        coVerify { watchlistDao.addToWatchlist(match { it.symbol == "C" && it.addedAt == 1_700_000_000_000L }) }
    }

    @Test
    fun `removing a stock hands back the date it was added`() = runTest {
        coEvery { watchlistDao.getWatchlistSyncByGroup(group) } returns
            listOf(WatchlistEntity("C", "Company C", 2, group, addedAt = 123L))

        val addedAt = repo.removeFromWatchlist("C", group)

        assertEquals(123L, addedAt)
        coVerify { watchlistDao.removeFromWatchlistInGroup("C", group) }
    }

    @Test
    fun `removing a stock that is not in the group still removes and has no date to hand back`() = runTest {
        coEvery { watchlistDao.getWatchlistSyncByGroup(group) } returns emptyList()

        val addedAt = repo.removeFromWatchlist("C", group)

        assertNull(addedAt)
        coVerify { watchlistDao.removeFromWatchlistInGroup("C", group) }
    }

    @Test
    fun `undoing twice does not duplicate the stock`() = runTest {
        holding(listOf("A", "C", "D"))                 // C is already back, e.g. from a first undo

        repo.restoreToWatchlist("C", "Company C", group, index = 1)

        // The stale copy is left out of the renumbering and C is written once, at the requested index.
        coVerify(exactly = 1) { watchlistDao.addToWatchlist(match { it.symbol == "C" && it.position == 1 }) }
        coVerify(exactly = 0) { watchlistDao.updatePosition("C", group, any()) }
        coVerify { watchlistDao.updatePosition("D", group, 2) }
    }
}
