package com.saurabh.financewidget.data.api

import com.saurabh.financewidget.data.model.YahooChartResponse
import com.saurabh.financewidget.data.model.YahooQuoteResponse
import com.saurabh.financewidget.data.model.YahooSearchResponse
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface YahooFinanceApiService {

    @GET("v8/finance/chart/{symbol}")
    suspend fun getQuote(
        @Path("symbol") symbol: String,
        @Query("interval") interval: String = "1d",
        @Query("range") range: String = "1d",
        @Query("includePrePost") includePrePost: Boolean = false
    ): Response<YahooChartResponse>

    @GET("v8/finance/chart/{symbol}")
    suspend fun getChartData(
        @Path("symbol") symbol: String,
        @Query("interval") interval: String,
        @Query("range") range: String,
        @Query("includePrePost") includePrePost: Boolean = false
    ): Response<YahooChartResponse>

    @GET("v1/finance/search")
    suspend fun searchSymbol(
        @Query("q") query: String,
        @Query("quotesCount") quotesCount: Int = 20,
        @Query("newsCount") newsCount: Int = 0,
        @Query("enableFuzzyQuery") enableFuzzyQuery: Boolean = false,
        @Query("quotesQueryId") quotesQueryId: String = "tss_match_phrase_query"
    ): Response<YahooSearchResponse>

    @GET("v7/finance/quote")
    suspend fun getQuoteDetails(
        @Query("symbols") symbol: String,
        @Query("fields") fields: String = "regularMarketOpen,regularMarketPreviousClose,marketCap,regularMarketChange,regularMarketChangePercent"
    ): Response<YahooQuoteResponse>
}
