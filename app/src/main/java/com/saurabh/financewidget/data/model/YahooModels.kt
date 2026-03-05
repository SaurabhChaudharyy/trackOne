package com.saurabh.financewidget.data.model

import com.google.gson.annotations.SerializedName


data class YahooChartResponse(
    @SerializedName("chart") val chart: YahooChart?
)

data class YahooChart(
    @SerializedName("result") val result: List<YahooChartResult>?,
    @SerializedName("error") val error: YahooError?
)

data class YahooChartResult(
    @SerializedName("meta")       val meta: YahooMeta,
    @SerializedName("timestamp")  val timestamps: List<Long>?,
    @SerializedName("indicators") val indicators: YahooIndicators?
)

data class YahooMeta(
    @SerializedName("symbol")                    val symbol: String = "",
    @SerializedName("currency")                  val currency: String = "USD",
    @SerializedName("exchangeName")              val exchangeName: String = "",
    @SerializedName("instrumentType")            val instrumentType: String = "",
    @SerializedName("regularMarketPrice")        val regularMarketPrice: Double = 0.0,
    @SerializedName("previousClose")             val previousClose: Double = 0.0,
    @SerializedName("chartPreviousClose")        val chartPreviousClose: Double = 0.0,
    @SerializedName("regularMarketOpen")         val regularMarketOpen: Double = 0.0,
    @SerializedName("regularMarketDayHigh")      val regularMarketDayHigh: Double = 0.0,
    @SerializedName("regularMarketDayLow")       val regularMarketDayLow: Double = 0.0,
    @SerializedName("regularMarketVolume")       val regularMarketVolume: Long = 0L,
    @SerializedName("regularMarketTime")         val regularMarketTime: Long = 0L,
    @SerializedName("regularMarketChange")       val regularMarketChange: Double = 0.0,
    @SerializedName("regularMarketChangePercent") val regularMarketChangePercent: Double = 0.0,
    @SerializedName("longName")                  val longName: String = "",
    @SerializedName("shortName")                 val shortName: String = "",
    @SerializedName("marketCap")                 val marketCap: Long = 0L,
    @SerializedName("fiftyTwoWeekHigh")          val fiftyTwoWeekHigh: Double = 0.0,
    @SerializedName("fiftyTwoWeekLow")           val fiftyTwoWeekLow: Double = 0.0,
    @SerializedName("trailingPE")                val trailingPE: Double = 0.0
) {
    // chartPreviousClose is always populated by Yahoo's chart endpoint;
    // previousClose is only present on some responses — use the reliable one.
    val effectivePreviousClose: Double
        get() = if (previousClose != 0.0) previousClose else chartPreviousClose

    val change: Double
        get() = if (regularMarketChange != 0.0) regularMarketChange
                else regularMarketPrice - effectivePreviousClose

    val changePercent: Double
        get() = if (regularMarketChangePercent != 0.0) regularMarketChangePercent
                else if (effectivePreviousClose != 0.0)
                    ((regularMarketPrice - effectivePreviousClose) / effectivePreviousClose) * 100.0
                else 0.0

    val displayName: String
        get() = longName.ifEmpty { shortName }.ifEmpty { symbol }
}

data class YahooIndicators(
    @SerializedName("quote") val quote: List<YahooQuote>?
)

data class YahooQuote(
    @SerializedName("open")   val open: List<Double?>?,
    @SerializedName("high")   val high: List<Double?>?,
    @SerializedName("low")    val low: List<Double?>?,
    @SerializedName("close")  val close: List<Double?>?,
    @SerializedName("volume") val volume: List<Long?>?
)

data class YahooError(
    @SerializedName("code")        val code: String = "",
    @SerializedName("description") val description: String = ""
)

data class YahooQuoteResponse(
    @SerializedName("quoteResponse") val quoteResponse: YahooQuoteContainer?
)

data class YahooQuoteContainer(
    @SerializedName("result") val result: List<YahooQuoteItem>?,
    @SerializedName("error") val error: YahooError?
)

data class YahooQuoteItem(
    @SerializedName("symbol")                 val symbol: String = "",
    @SerializedName("longName")               val longName: String = "",
    @SerializedName("shortName")              val shortName: String = "",
    @SerializedName("regularMarketPrice")     val regularMarketPrice: Double = 0.0,
    @SerializedName("regularMarketChange")    val regularMarketChange: Double = 0.0,
    @SerializedName("regularMarketChangePercent") val regularMarketChangePercent: Double = 0.0,
    @SerializedName("regularMarketDayHigh")   val regularMarketDayHigh: Double = 0.0,
    @SerializedName("regularMarketDayLow")    val regularMarketDayLow: Double = 0.0,
    @SerializedName("regularMarketOpen")      val regularMarketOpen: Double = 0.0,
    @SerializedName("regularMarketPreviousClose") val regularMarketPreviousClose: Double = 0.0,
    @SerializedName("regularMarketVolume")    val regularMarketVolume: Long = 0L,
    @SerializedName("marketCap")              val marketCap: Long = 0L,
    @SerializedName("currency")               val currency: String = "USD",
    @SerializedName("fullExchangeName")       val fullExchangeName: String = "",
    @SerializedName("trailingPE")             val trailingPE: Double = 0.0,
    @SerializedName("fiftyTwoWeekHigh")       val fiftyTwoWeekHigh: Double = 0.0,
    @SerializedName("fiftyTwoWeekLow")        val fiftyTwoWeekLow: Double = 0.0,
    @SerializedName("dividendYield")          val dividendYield: Double = 0.0
)

data class YahooSearchResponse(
    @SerializedName("quotes") val quotes: List<YahooSearchResult>?
)

data class YahooSearchResult(
    @SerializedName("symbol")       val symbol: String = "",
    @SerializedName("longname")     val longName: String = "",
    @SerializedName("shortname")    val shortName: String = "",
    @SerializedName("exchDisp")     val exchange: String = "",
    @SerializedName("typeDisp")     val typeDisplay: String = "",
    @SerializedName("quoteType")    val quoteType: String = ""
) {
    val displayName: String
        get() = longName.ifEmpty { shortName }.ifEmpty { symbol }

    val isTrackable: Boolean
        get() = quoteType in listOf("EQUITY", "ETF", "MUTUALFUND", "INDEX", "CRYPTOCURRENCY", "CURRENCY")
}
