package app.trackone.data.api

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackoffInterceptorTest {

    private var now = 5_000_000L
    private val backoff = RateLimitBackoff { now }
    private val interceptor = BackoffInterceptor(backoff)

    private fun request(url: String) = Request.Builder().url(url).build()

    private fun response(request: Request, code: Int, retryAfter: String? = null) =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("m")
            .apply { if (retryAfter != null) header("Retry-After", retryAfter) }
            .body("".toResponseBody(null)).build()

    private fun chain(request: Request, answer: Response) = mockk<Interceptor.Chain> {
        every { request() } returns request
        every { proceed(any()) } returns answer
    }

    private val quote = "https://query1.finance.yahoo.com/v8/finance/chart/AAPL"

    @Test
    fun `a request goes through while nothing is limiting us`() {
        val req = request(quote)
        val chain = chain(req, response(req, 200))

        val out = interceptor.intercept(chain)

        assertEquals(200, out.code)
        verify(exactly = 1) { chain.proceed(req) }
    }

    @Test
    fun `a 429 from Yahoo starts a pause and is passed back as it came`() {
        val req = request(quote)
        val chain = chain(req, response(req, 429, retryAfter = "30"))

        val out = interceptor.intercept(chain)

        assertEquals(429, out.code)
        assertTrue(backoff.isPaused())
        assertEquals(30_000L, backoff.remainingMs())
    }

    @Test
    fun `while paused no request leaves the phone and the caller gets a 429`() {
        backoff.onResponse(429, null)
        val req = request(quote)
        val chain = chain(req, response(req, 200))

        val out = interceptor.intercept(chain)

        assertEquals(429, out.code)
        verify(exactly = 0) { chain.proceed(any()) }
    }

    @Test
    fun `requests resume once the pause is over`() {
        backoff.onResponse(429, null)
        now += 60_000
        val req = request(quote)
        val chain = chain(req, response(req, 200))

        val out = interceptor.intercept(chain)

        assertEquals(200, out.code)
        verify(exactly = 1) { chain.proceed(req) }
    }

    private fun timingOutChain(request: Request, failure: java.io.IOException) = mockk<Interceptor.Chain> {
        every { request() } returns request
        every { proceed(any()) } throws failure
    }

    private fun attempt(chain: Interceptor.Chain): Throwable? =
        try { interceptor.intercept(chain); null } catch (e: java.io.IOException) { e }

    @Test
    fun `timeouts are passed on to the caller and a run of them starts a pause`() {
        val req = request(quote)
        val failure = java.net.SocketTimeoutException("timeout")
        val chain = timingOutChain(req, failure)

        repeat(RateLimitBackoff.TIMEOUTS_BEFORE_PAUSE) { assertEquals(failure, attempt(chain)) }

        assertTrue(backoff.isPaused())
    }

    @Test
    fun `a call that timed out as a whole counts the same as a socket timeout`() {
        val req = request(quote)
        val chain = timingOutChain(req, java.io.InterruptedIOException("timeout"))

        repeat(RateLimitBackoff.TIMEOUTS_BEFORE_PAUSE) { attempt(chain) }

        assertTrue(backoff.isPaused())
    }

    @Test
    fun `being offline is not a timeout and never starts a pause`() {
        val req = request(quote)
        val chain = timingOutChain(req, java.net.UnknownHostException("no network"))

        repeat(RateLimitBackoff.TIMEOUTS_BEFORE_PAUSE * 2) { attempt(chain) }

        assertFalse(backoff.isPaused())
    }

    @Test
    fun `timeouts from another host are not counted`() {
        val req = request("https://example.com/other")
        val chain = timingOutChain(req, java.net.SocketTimeoutException("timeout"))

        repeat(RateLimitBackoff.TIMEOUTS_BEFORE_PAUSE * 2) { attempt(chain) }

        assertFalse(backoff.isPaused())
    }

    @Test
    fun `a host that is not Yahoo is never held back or counted`() {
        backoff.onResponse(429, null)
        val req = request("https://example.com/other")
        val chain = chain(req, response(req, 429))

        val out = interceptor.intercept(chain)

        assertEquals(429, out.code)
        verify(exactly = 1) { chain.proceed(req) }
        assertFalse(backoff.remainingMs() > 60_000L)
    }
}
