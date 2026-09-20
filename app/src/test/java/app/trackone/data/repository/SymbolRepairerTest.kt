package app.trackone.data.repository

import app.trackone.data.database.AssetType
import app.trackone.data.database.NetWorthAssetEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SymbolRepairerTest {

    private lateinit var resolver: SymbolResolver
    private lateinit var attempts: RepairAttempts
    private lateinit var repairer: SymbolRepairer
    private val lastAttempt = mutableMapOf<String, Long>()
    private val week = SymbolRepairer.RETRY_AFTER_MS
    private val now = 1_800_000_000_000L

    @Before
    fun setUp() {
        resolver = mockk()
        attempts = mockk()
        every { attempts.lastAttempt(any()) } answers { lastAttempt[firstArg()] ?: 0L }
        every { attempts.record(any(), any()) } answers {
            firstArg<Collection<String>>().forEach { lastAttempt[it] = secondArg() }
        }
        repairer = SymbolRepairer(resolver, attempts)
    }

    private fun holding(id: Long, name: String, type: AssetType = AssetType.STOCK_IN, isin: String? = null) =
        NetWorthAssetEntity(id = id, name = name, assetType = type, currentValue = 100.0, isin = isin)

    private fun confident(symbol: String, name: String = "Matched Co", basis: MatchBasis = MatchBasis.NAME) =
        SymbolResolution.Confident(symbol, name, basis)

    @Test
    fun `a failed company-name holding is proposed for renaming to its ticker`() = runTest {
        val itc = holding(1, "ITC HOTELS LIMITED", isin = "INE379A01028")
        coEvery { resolver.resolve("ITC HOTELS LIMITED", "INE379A01028", AssetType.STOCK_IN, any()) } returns
            confident("ITCHOTELS", "ITC Hotels Limited", MatchBasis.ISIN)

        val plan = repairer.findRepairs(listOf(itc), listOf(itc), now)

        assertEquals(listOf(SymbolRepair(1, "ITC HOTELS LIMITED", "ITCHOTELS", MatchBasis.ISIN, "ITC Hotels Limited")), plan)
    }

    @Test
    fun `it only proposes, never changes anything itself`() = runTest {
        val a = holding(1, "Reliance Industries")
        coEvery { resolver.resolve(any(), any(), any(), any()) } returns confident("RELIANCE")

        repairer.findRepairs(listOf(a), listOf(a), now)

        // the only side effect is recording that an attempt was made (no DAO is even passed in)
        verify { attempts.record(listOf("1|RELIANCE INDUSTRIES"), now) }
    }

    @Test
    fun `holdings without a ticker to fix are never looked up`() = runTest {
        val plan = repairer.findRepairs(
            listOf(holding(1, "Savings", AssetType.BANK), holding(2, "Axis Fund", AssetType.MF), holding(3, "GOLD", AssetType.GOLD)),
            emptyList(), now
        )

        assertTrue(plan.isEmpty())
        coVerify(exactly = 0) { resolver.resolve(any(), any(), any(), any()) }
    }

    @Test
    fun `renaming to a ticker another row already holds is refused, to avoid a duplicate holding`() = runTest {
        val named = holding(1, "ITC HOTELS LIMITED")
        val already = holding(2, "ITCHOTELS")            // e.g. from a Zerodha import
        coEvery { resolver.resolve(any(), any(), any(), any()) } returns confident("ITCHOTELS")

        assertTrue(repairer.findRepairs(listOf(named), listOf(named, already), now).isEmpty())
    }

    @Test
    fun `an existing NSE row counts as a duplicate whether or not it carries the suffix`() = runTest {
        val named = holding(1, "ITC HOTELS LIMITED")
        val already = holding(2, "ITCHOTELS.NS")         // added through the add dialog, which stores the suffix
        coEvery { resolver.resolve(any(), any(), any(), any()) } returns confident("ITCHOTELS")

        assertTrue(repairer.findRepairs(listOf(named), listOf(named, already), now).isEmpty())
    }

    @Test
    fun `resolving to the ticker it already has changes nothing`() = runTest {
        val a = holding(1, "RELIANCE")                   // lookup failed for some other reason (e.g. Yahoo hiccup)
        coEvery { resolver.resolve(any(), any(), any(), any()) } returns confident("RELIANCE")

        assertTrue(repairer.findRepairs(listOf(a), listOf(a), now).isEmpty())
    }

    @Test
    fun `an ambiguous or unknown company is left alone, still flagged`() = runTest {
        val a = holding(1, "Alphabet Inc.", AssetType.STOCK_US)
        val b = holding(2, "Nonexistent Corp")
        coEvery { resolver.resolve("Alphabet Inc.", any(), any(), any()) } returns SymbolResolution.Ambiguous(listOf("GOOG", "GOOGL"))
        coEvery { resolver.resolve("Nonexistent Corp", any(), any(), any()) } returns SymbolResolution.NotFound

        assertTrue(repairer.findRepairs(listOf(a, b), listOf(a, b), now).isEmpty())
    }

    // ── retry budget ─────────────────────────────────────────────────────────

    @Test
    fun `a holding that got a definite answer is not retried for a week`() = runTest {
        val a = holding(1, "Nonexistent Corp")
        coEvery { resolver.resolve(any(), any(), any(), any()) } returns SymbolResolution.NotFound

        repairer.findRepairs(listOf(a), listOf(a), now)
        repairer.findRepairs(listOf(a), listOf(a), now + week - 1)
        coVerify(exactly = 1) { resolver.resolve(any(), any(), any(), any()) }

        repairer.findRepairs(listOf(a), listOf(a), now + week)
        coVerify(exactly = 2) { resolver.resolve(any(), any(), any(), any()) }
    }

    @Test
    fun `an attempt made while offline does not use up the retry budget`() = runTest {
        val a = holding(1, "ITC HOTELS LIMITED")
        coEvery { resolver.resolve(any(), any(), any(), any()) } returns SymbolResolution.Unavailable

        repairer.findRepairs(listOf(a), listOf(a), now)
        repairer.findRepairs(listOf(a), listOf(a), now + 60_000)

        coVerify(exactly = 2) { resolver.resolve(any(), any(), any(), any()) }
    }

    @Test
    fun `a rename that succeeds is not retried under its old name either way`() = runTest {
        // the attempt key includes the name, so once renamed the row is judged afresh under its new name
        val a = holding(1, "ITC HOTELS LIMITED")
        coEvery { resolver.resolve(any(), any(), any(), any()) } returns confident("ITCHOTELS")

        repairer.findRepairs(listOf(a), listOf(a), now)

        assertEquals(setOf("1|ITC HOTELS LIMITED"), lastAttempt.keys)
    }
}
