package app.trackone.data.model

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The session times Yahoo sends in every chart response, read exactly as the real API shapes them. */
class YahooTradingPeriodTest {

    // Trimmed from a real ^NSEI response: NSE has no pre-market, so pre starts and ends at the open.
    private val nseJson = """
        {"chart":{"result":[{"meta":{"currency":"INR","symbol":"^NSEI","exchangeName":"NSI",
        "regularMarketPrice":23146.15,"regularMarketTime":1792144867,"gmtoffset":19800,"timezone":"IST",
        "currentTradingPeriod":{
          "pre":{"timezone":"IST","start":1792122300,"end":1792122300,"gmtoffset":19800},
          "regular":{"timezone":"IST","start":1792122300,"end":1792144800,"gmtoffset":19800},
          "post":{"timezone":"IST","start":1792144800,"end":1792144800,"gmtoffset":19800}},
        "dataGranularity":"1d","range":"1d"},"timestamp":[1792122300],"indicators":{"quote":[{}]}}],"error":null}}
    """.trimIndent()

    // US equities: a real pre-market and after-hours either side of the regular session.
    private val nasdaqJson = """
        {"chart":{"result":[{"meta":{"currency":"USD","symbol":"^IXIC","regularMarketPrice":26140.033,
        "currentTradingPeriod":{
          "pre":{"timezone":"EDT","start":1792137600,"end":1792157400,"gmtoffset":-14400},
          "regular":{"timezone":"EDT","start":1792157400,"end":1792180800,"gmtoffset":-14400},
          "post":{"timezone":"EDT","start":1792180800,"end":1792195200,"gmtoffset":-14400}}},
        "timestamp":[1792157400],"indicators":{"quote":[{}]}}],"error":null}}
    """.trimIndent()

    private fun meta(json: String) = Gson().fromJson(json, YahooChartResponse::class.java).chart!!.result!!.first().meta

    @Test
    fun `the regular session is read with its start, end and exchange offset`() {
        val regular = meta(nseJson).currentTradingPeriod!!.regular!!

        assertEquals(1_792_122_300L, regular.start)
        assertEquals(1_792_144_800L, regular.end)
        assertEquals(19_800, regular.gmtoffset)
    }

    @Test
    fun `a market with no pre-market has an empty pre period`() {
        val pre = meta(nseJson).currentTradingPeriod!!.pre!!

        assertEquals(pre.start, pre.end)
    }

    @Test
    fun `extended hours are read for a market that has them`() {
        val periods = meta(nasdaqJson).currentTradingPeriod!!

        assertEquals(1_792_137_600L, periods.pre!!.start)
        assertEquals(1_792_195_200L, periods.post!!.end)
        assertEquals(-14_400, periods.regular!!.gmtoffset)
    }

    @Test
    fun `a response without session times still parses`() {
        val json = """{"chart":{"result":[{"meta":{"symbol":"X","regularMarketPrice":1.0},"timestamp":[],"indicators":{"quote":[]}}],"error":null}}"""

        assertNull(meta(json).currentTradingPeriod)
        assertNotNull(meta(json))
    }
}
