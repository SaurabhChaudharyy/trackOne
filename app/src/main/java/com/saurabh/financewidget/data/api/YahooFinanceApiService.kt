package com.saurabh.financewidget.data.api

import com.saurabh.financewidget.data.model.YahooChartResponse
import com.saurabh.financewidget.data.model.YahooQuoteResponse
import com.saurabh.financewidget.data.model.YahooSearchResponse
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface YahooFinanceApiService {

    /**
     * Get real-time quote + basic info for a symbol.
     *
     * Examples:
     *  US stocks  → "AAPL", "NVDA", "GOOGL"
     *  NSE India  → "TCS.NS", "RELIANCE.NS", "INFY.NS"
     *  BSE India  → "TCS.BO", "RELIANCE.BO"
     *  Indices    → "^NSEI" (NIFTY 50), "^BSESN" (SENSEX), "^IXIC" (NASDAQ), "^GSPC" (S&P 500)
     *  Crypto     → "BTC-USD", "ETH-USD"
     *  Forex      → "USDINR=X", "EURUSD=X"
     */
    @GET("v8/finance/chart/{symbol}")
    suspend fun getQuote(
        @Path("symbol") symbol: String,
        @Query("interval") interval: String = "1d",
        @Query("range") range: String = "1d",
        @Query("includePrePost") includePrePost: Boolean = false
    ): Response<YahooChartResponse>

    /**
     * Get historical OHLCV candle data for charting.
     *
     * @param interval  "1m","5m","15m","30m","60m","1d","1wk","1mo"
     * @param range     "1d","5d","1mo","3mo","6mo","1y","2y","5y","10y","ytd","max"
     */
    @GET("v8/finance/chart/{symbol}")
    suspend fun getChartData(
        @Path("symbol") symbol: String,
        @Query("interval") interval: String,
        @Query("range") range: String,
        @Query("includePrePost") includePrePost: Boolean = false
    ): Response<YahooChartResponse>

    /**
     * Search for stocks by keyword — returns symbols and names.
     */
    @GET("v1/finance/search")
    suspend fun searchSymbol(
        @Query("q") query: String,
        @Query("quotesCount") quotesCount: Int = 20,
        @Query("newsCount") newsCount: Int = 0,
        @Query("enableFuzzyQuery") enableFuzzyQuery: Boolean = false,
        @Query("quotesQueryId") quotesQueryId: String = "tss_match_phrase_query"
    ): Response<YahooSearchResponse>

    /**
     * Fetch full quote details for a symbol (marketCap, regularMarketOpen, etc.).
     * The /v7/finance/quote endpoint is more reliable for these supplemental fields
     * than the chart endpoint.
     */
    @GET("v7/finance/quote")
    suspend fun getQuoteDetails(
        @Query("symbols") symbol: String,
        @Query("fields") fields: String = "regularMarketOpen,regularMarketPreviousClose,marketCap,regularMarketChange,regularMarketChangePercent"
    ): Response<YahooQuoteResponse>
}
