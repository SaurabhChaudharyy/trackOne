package app.trackone.data.repository

import app.trackone.data.database.AssetType
import app.trackone.data.model.YahooSearchResult
import app.trackone.data.repository.SymbolResolution.Ambiguous
import app.trackone.data.repository.SymbolResolution.Confident
import app.trackone.data.repository.SymbolResolution.NotFound
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Candidates below are the shapes Yahoo's search actually returned (queried live), noise included. */
class SymbolMatcherTest {

    private fun r(symbol: String, short: String, long: String, exch: String, type: String = "EQUITY") =
        YahooSearchResult(symbol = symbol, shortName = short, longName = long, exchange = exch, quoteType = type)

    private val itcHotels = listOf(
        r("ITCHOTELS.NS", "ITC HOTELS LIMITED", "ITC Hotels Limited", "NSE"),
        r("ITCHOTELS.BO", "ITC Hotels Limited", "ITC Hotels Limited", "Bombay")
    )

    private val reliance = listOf(
        r("RELIANCE.NS", "RELIANCE INDUSTRIES LTD", "Reliance Industries Limited", "NSE"),
        r("RIGD.IL", "RELIANCE INDUSTRIES LIMITED GDR", "Reliance Industries Limited", "International Orderbook - London"),
        r("RELIANCE.BO", "RELIANCE INDUSTRIES LTD.", "Reliance Industries Limited", "Bombay"),
        r("RLI.SG", "Reliance Industries GDR", "Reliance Industries Ltd", "Stuttgart"),
        r("RLI.VI", "RELIANCE INDS-SPONS GDR 144A", "Reliance Industries Limited", "Vienna")
    )

    private val apple = listOf(
        r("AAPL", "Apple Inc.", "Apple Inc.", "NASDAQ"),
        r("SAAPL=F", "Apple Inc Stock Futures,Dec-202", "Apple Inc Single Stock futures", "Chicago Mercantile Exchange", "FUTURE"),
        r("AAPL.TO", "APPLE CDR (CAD HEDGED)", "Apple Inc.", "Toronto"),
        r("APC.F", "Apple Inc.                    R", "Apple Inc.", "Frankfurt"),
        r("APC.DE", "Apple Inc.                    R", "Apple Inc.", "XETRA")
    )

    private val alphabet = listOf(
        r("GOOG", "Alphabet Inc.", "Alphabet Inc.", "NASDAQ"),
        r("GOOGL", "Alphabet Inc.", "Alphabet Inc.", "NASDAQ"),
        r("GOOG.TO", "ALPHABET CDR (CAD HEDGED)", "Alphabet Inc.", "Toronto")
    )

    private val bitcoin = listOf(
        r("BTC-USD", "Bitcoin USD", "Bitcoin USD", "CCC", "CRYPTOCURRENCY"),
        r("IBIT", "iShares Bitcoin Trust ETF", "iShares Bitcoin Trust ETF", "NASDAQ", "ETF"),
        r("BCH-USD", "Bitcoin Cash USD", "Bitcoin Cash USD", "CCC", "CRYPTOCURRENCY"),
        r("BTC=F", "Bitcoin Futures,Sep-2026", "", "Chicago Mercantile Exchange", "FUTURE")
    )

    // ── Indian stocks ────────────────────────────────────────────────────────

    @Test
    fun `an ISIN that returns the NSE and BSE listings of one company resolves to the NSE ticker`() {
        val result = SymbolMatcher.choose("INE379A01028", itcHotels, AssetType.STOCK_IN)

        assertEquals(Confident("ITCHOTELS", "ITC Hotels Limited", MatchBasis.ISIN), result)
    }

    @Test
    fun `a company name matches despite Ltd vs Limited and case, and picks NSE over BSE and foreign GDRs`() {
        val result = SymbolMatcher.choose("Reliance Industries", reliance, AssetType.STOCK_IN)

        assertEquals(Confident("RELIANCE", "Reliance Industries Limited", MatchBasis.NAME), result)
    }

    @Test
    fun `a BSE-only listing keeps its exchange suffix because a bare code would be looked up on NSE`() {
        val bseOnly = listOf(r("ABCD.BO", "ABCD LTD", "ABCD Limited", "Bombay"))

        assertEquals(Confident("ABCD.BO", "ABCD Limited", MatchBasis.NAME), SymbolMatcher.choose("ABCD Limited", bseOnly, AssetType.STOCK_IN))
    }

    @Test
    fun `foreign GDR listings alone are never accepted for an Indian stock`() {
        val onlyGdr = listOf(r("RLI.SG", "Reliance Industries GDR", "Reliance Industries Ltd", "Stuttgart"))

        assertEquals(NotFound, SymbolMatcher.choose("Reliance Industries", onlyGdr, AssetType.STOCK_IN))
    }

    @Test
    fun `a partial name does not match a longer company name`() {
        // "ITC" is ITC Ltd, not ITC Hotels — matching on a prefix would price the wrong company.
        assertEquals(NotFound, SymbolMatcher.choose("ITC", itcHotels, AssetType.STOCK_IN))
    }

    @Test
    fun `an ISIN query whose results are different companies is not trusted`() {
        val noise = listOf(
            r("AAA.NS", "AAA LTD", "AAA Limited", "NSE"),
            r("BBB.NS", "BBB LTD", "BBB Limited", "NSE")
        )

        assertTrue(SymbolMatcher.choose("INE000000000", noise, AssetType.STOCK_IN) !is Confident)
    }

    // ── US stocks ────────────────────────────────────────────────────────────

    @Test
    fun `Apple resolves to AAPL and ignores futures and Toronto, Frankfurt and XETRA copies`() {
        assertEquals(Confident("AAPL", "Apple Inc.", MatchBasis.NAME), SymbolMatcher.choose("Apple Inc.", apple, AssetType.STOCK_US))
        assertEquals(Confident("AAPL", "Apple Inc.", MatchBasis.NAME), SymbolMatcher.choose("APPLE INC", apple, AssetType.STOCK_US))
    }

    @Test
    fun `Alphabet is ambiguous because GOOG and GOOGL share one name, so it is never guessed`() {
        val result = SymbolMatcher.choose("Alphabet Inc.", alphabet, AssetType.STOCK_US)

        assertEquals(Ambiguous(listOf("GOOG", "GOOGL")), result)
    }

    // ── Crypto ───────────────────────────────────────────────────────────────

    @Test
    fun `Bitcoin resolves to the BTC-USD coin, not the ETF, Bitcoin Cash or futures`() {
        assertEquals(Confident("BTC-USD", "Bitcoin USD", MatchBasis.NAME), SymbolMatcher.choose("Bitcoin", bitcoin, AssetType.CRYPTO))
    }

    @Test
    fun `assets without a market ticker are never resolved`() {
        for (type in listOf(AssetType.MF, AssetType.CASH, AssetType.BANK, AssetType.GOLD, AssetType.SILVER)) {
            assertEquals(type.name, NotFound, SymbolMatcher.choose("Reliance Industries", reliance, type))
        }
    }

    @Test
    fun `no candidates means not found`() {
        assertEquals(NotFound, SymbolMatcher.choose("Whatever", emptyList(), AssetType.STOCK_IN))
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    @Test
    fun `ISINs and tickers are told apart from company names`() {
        assertTrue(SymbolMatcher.isIsin("INE379A01028"))
        assertTrue(SymbolMatcher.isIsin("US0378331005"))
        assertTrue(!SymbolMatcher.isIsin("ITC HOTELS LIMITED"))
        assertTrue(!SymbolMatcher.isIsin("RELIANCE"))

        assertTrue(SymbolMatcher.looksLikeTicker("RELIANCE"))
        assertTrue(SymbolMatcher.looksLikeTicker("M&M"))
        assertTrue(SymbolMatcher.looksLikeTicker("BAJAJ-AUTO"))
        assertTrue(SymbolMatcher.looksLikeTicker("TCS.NS"))
        assertTrue(!SymbolMatcher.looksLikeTicker("ITC HOTELS LIMITED"))
        assertTrue(!SymbolMatcher.looksLikeTicker("Apple Inc."))
    }
}
