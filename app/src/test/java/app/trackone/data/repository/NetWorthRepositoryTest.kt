package app.trackone.data.repository

import android.content.Context
import android.content.SharedPreferences
import app.trackone.data.api.YahooFinanceApiService
import app.trackone.data.database.AssetType
import app.trackone.data.database.FinanceDatabase
import app.trackone.data.database.NetWorthDao
import app.trackone.data.model.YahooChart
import app.trackone.data.model.YahooChartResponse
import app.trackone.data.model.YahooChartResult
import app.trackone.data.model.YahooMeta
import app.trackone.utils.Resource
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

/**
 * Only fetchLivePrice/fetchUsdInrRate are covered here — refreshNetWorthAssets wraps its writes
 * in Room's `withTransaction`, which needs a real (or in-memory) Room database to exercise
 * meaningfully; that's instrumented-test territory, not a plain JVM unit test.
 */
class NetWorthRepositoryTest {

    private lateinit var apiService: YahooFinanceApiService
    private lateinit var netWorthDao: NetWorthDao
    private lateinit var database: FinanceDatabase
    private lateinit var context: Context
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private lateinit var repository: NetWorthRepository

    @Before
    fun setUp() {
        apiService = mockk()
        netWorthDao = mockk(relaxed = true)
        database = mockk(relaxed = true)
        editor = mockk(relaxed = true)
        sharedPreferences = mockk {
            every { getFloat(any(), any()) } answers { secondArg() }
            every { edit() } returns editor
        }
        every { editor.putFloat(any(), any()) } returns editor
        context = mockk<Context> {
            every { getSharedPreferences(any(), any()) } returns sharedPreferences
        }
        repository = NetWorthRepository(database, netWorthDao, apiService, context)
    }

    private fun chartResponse(currency: String, price: Double): Response<YahooChartResponse> =
        Response.success(
            YahooChartResponse(
                chart = YahooChart(
                    result = listOf(
                        YahooChartResult(
                            meta = YahooMeta(currency = currency, regularMarketPrice = price),
                            timestamps = null,
                            indicators = null
                        )
                    ),
                    error = null
                )
            )
        )

    @Test
    fun `INR-denominated stock price passes through unconverted`() = runTest {
        coEvery { apiService.getQuote("TCS.NS") } returns chartResponse("INR", 3500.0)

        val result = repository.fetchLivePrice("TCS", AssetType.STOCK_IN)

        assertTrue(result is Resource.Success)
        assertEquals(3500.0, (result as Resource.Success).data, 0.0001)
    }

    @Test
    fun `Indian stock symbol is normalised with an NS suffix before the API call`() = runTest {
        coEvery { apiService.getQuote("INFY.NS") } returns chartResponse("INR", 1500.0)

        repository.fetchLivePrice("infy", AssetType.STOCK_IN)

        // Would throw "no answer found" if the symbol weren't normalised, since only
        // "INFY.NS" was stubbed above.
    }

    @Test
    fun `USD stock price is converted using a caller-supplied rate, without an extra FX call`() = runTest {
        coEvery { apiService.getQuote("AAPL") } returns chartResponse("USD", 10.0)

        val result = repository.fetchLivePrice("AAPL", AssetType.STOCK_US, usdInrRate = 83.0)

        assertTrue(result is Resource.Success)
        assertEquals(830.0, (result as Resource.Success).data, 0.0001)
    }

    @Test
    fun `gold price is converted from troy ounce to grams, in INR`() = runTest {
        // GC=F quotes come back in USD per troy ounce.
        coEvery { apiService.getQuote("GC=F") } returns chartResponse("USD", 311.035)

        val result = repository.fetchLivePrice("GC=F", AssetType.GOLD, usdInrRate = 10.0)

        assertTrue(result is Resource.Success)
        // 311.035 * 10 (fx) = 3110.35 INR per troy ounce -> /31.1035 = 100.0 INR/gram
        assertEquals(100.0, (result as Resource.Success).data, 0.0001)
    }

    @Test
    fun `HTTP failure surfaces as a Resource Error, not an exception`() = runTest {
        coEvery { apiService.getQuote("BADSYM") } returns Response.error(
            500, okhttp3.ResponseBody.create(null, "")
        )

        val result = repository.fetchLivePrice("BADSYM", AssetType.STOCK_US)

        assertTrue(result is Resource.Error)
    }

    @Test
    fun `a non-positive price is treated as an error, not a valid zero-value asset`() = runTest {
        coEvery { apiService.getQuote("DELISTED") } returns chartResponse("USD", 0.0)

        val result = repository.fetchLivePrice("DELISTED", AssetType.STOCK_US)

        assertTrue(result is Resource.Error)
    }

    @Test
    fun `an exception from the API call is caught and returned as a Resource Error`() = runTest {
        coEvery { apiService.getQuote(any()) } throws RuntimeException("network down")

        val result = repository.fetchLivePrice("AAPL", AssetType.STOCK_US)

        assertTrue(result is Resource.Error)
    }

    @Test
    fun `fetchUsdInrRate caches a successful live rate for later fallback`() = runTest {
        coEvery { apiService.getQuote("USDINR=X") } returns chartResponse("USD", 84.5)
        val cachedValue = slot<Float>()
        every { editor.putFloat(any(), capture(cachedValue)) } returns editor

        val rate = repository.fetchUsdInrRate()

        assertEquals(84.5, rate, 0.0001)
        assertEquals(84.5f, cachedValue.captured, 0.0001f)
    }

    @Test
    fun `fetchUsdInrRate falls back to the last cached rate when the live call fails`() = runTest {
        every { sharedPreferences.getFloat(any(), any()) } returns 80.0f
        coEvery { apiService.getQuote("USDINR=X") } throws RuntimeException("network down")

        val rate = repository.fetchUsdInrRate()

        assertEquals(80.0, rate, 0.0001)
    }
}
