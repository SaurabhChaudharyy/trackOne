package app.trackone.data.repository

import app.trackone.data.api.YahooFinanceApiService
import app.trackone.data.database.AssetType
import app.trackone.data.model.YahooSearchResponse
import app.trackone.data.model.YahooSearchResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

class SymbolResolverTest {

    private lateinit var api: YahooFinanceApiService
    private lateinit var resolver: SymbolResolver

    private val itcIsin = "INE379A01028"

    private val itcHotels = listOf(
        YahooSearchResult("ITCHOTELS.NS", "ITC Hotels Limited", "ITC HOTELS LIMITED", "NSE", "", "EQUITY"),
        YahooSearchResult("ITCHOTELS.BO", "ITC Hotels Limited", "ITC Hotels Limited", "Bombay", "", "EQUITY")
    )

    @Before
    fun setUp() {
        api = mockk()
        resolver = SymbolResolver(api)
    }

    private fun ok(results: List<YahooSearchResult>) = Response.success(YahooSearchResponse(results))
    private fun httpError() = Response.error<YahooSearchResponse>(500, "".toResponseBody(null))
    private fun stub(query: String, response: Response<YahooSearchResponse>) {
        coEvery { api.searchSymbol(eq(query), any(), any(), any(), any()) } returns response
    }

    @Test
    fun `the ISIN is tried first and settles it without a name search`() = runTest {
        stub(itcIsin, ok(itcHotels))

        val result = resolver.resolve("ITC HOTELS LIMITED", itcIsin, AssetType.STOCK_IN)

        assertEquals(SymbolResolution.Confident("ITCHOTELS", "ITC Hotels Limited", MatchBasis.ISIN), result)
        coVerify(exactly = 0) { api.searchSymbol(eq("ITC HOTELS LIMITED"), any(), any(), any(), any()) }
    }

    @Test
    fun `when the ISIN finds nothing it falls back to the company name`() = runTest {
        stub(itcIsin, ok(emptyList()))
        stub("ITC HOTELS LIMITED", ok(itcHotels))

        val result = resolver.resolve("ITC HOTELS LIMITED", itcIsin, AssetType.STOCK_IN)

        assertEquals(SymbolResolution.Confident("ITCHOTELS", "ITC Hotels Limited", MatchBasis.NAME), result)
    }

    @Test
    fun `a row with no ISIN is resolved by name alone`() = runTest {
        stub("Reliance Industries", ok(listOf(
            YahooSearchResult("RELIANCE.NS", "Reliance Industries Limited", "RELIANCE INDUSTRIES LTD", "NSE", "", "EQUITY")
        )))

        val result = resolver.resolve("Reliance Industries", null, AssetType.STOCK_IN)

        assertEquals(SymbolResolution.Confident("RELIANCE", "Reliance Industries Limited", MatchBasis.NAME), result)
    }

    @Test
    fun `types with no market ticker never touch the network`() = runTest {
        assertEquals(SymbolResolution.NotFound, resolver.resolve("HDFC Savings", null, AssetType.BANK))
        assertEquals(SymbolResolution.NotFound, resolver.resolve("Axis Bluechip", null, AssetType.MF))

        coVerify(exactly = 0) { api.searchSymbol(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a failed search is unavailable, not "no such company", and is not remembered`() = runTest {
        stub("Reliance Industries", httpError())

        assertEquals(SymbolResolution.Unavailable, resolver.resolve("Reliance Industries", null, AssetType.STOCK_IN))
        assertEquals(SymbolResolution.Unavailable, resolver.resolve("Reliance Industries", null, AssetType.STOCK_IN))

        coVerify(exactly = 2) { api.searchSymbol(eq("Reliance Industries"), any(), any(), any(), any()) }
    }

    @Test
    fun `an exception from the search is swallowed rather than crashing an import`() = runTest {
        coEvery { api.searchSymbol(any(), any(), any(), any(), any()) } throws RuntimeException("offline")

        assertEquals(SymbolResolution.Unavailable, resolver.resolve("Anything", null, AssetType.STOCK_IN))
    }

    @Test
    fun `a definite answer is cached`() = runTest {
        stub(itcIsin, ok(itcHotels))

        resolver.resolve("ITC HOTELS LIMITED", itcIsin, AssetType.STOCK_IN, nowMs = 1_000)
        resolver.resolve("ITC HOTELS LIMITED", itcIsin, AssetType.STOCK_IN, nowMs = 60_000)

        coVerify(exactly = 1) { api.searchSymbol(eq(itcIsin), any(), any(), any(), any()) }
    }

    @Test
    fun `a search that hangs is abandoned so an import can't stall`() = runTest {
        coEvery { api.searchSymbol(any(), any(), any(), any(), any()) } coAnswers { delay(120_000); ok(itcHotels) }

        assertEquals(SymbolResolution.Unavailable, resolver.resolve("ITC HOTELS LIMITED", null, AssetType.STOCK_IN))
    }

    @Test
    fun `an ambiguous name is reported as ambiguous, not guessed`() = runTest {
        stub("Alphabet Inc.", ok(listOf(
            YahooSearchResult("GOOG", "Alphabet Inc.", "Alphabet Inc.", "NASDAQ", "", "EQUITY"),
            YahooSearchResult("GOOGL", "Alphabet Inc.", "Alphabet Inc.", "NASDAQ", "", "EQUITY")
        )))

        val result = resolver.resolve("Alphabet Inc.", null, AssetType.STOCK_US)

        assertTrue(result is SymbolResolution.Ambiguous)
    }

    @Test
    fun `a search that answers with no match is a definite not-found, unlike a failed one`() = runTest {
        stub("Totally Made Up Co", ok(emptyList()))

        assertEquals(SymbolResolution.NotFound, resolver.resolve("Totally Made Up Co", null, AssetType.STOCK_IN))
    }
}
