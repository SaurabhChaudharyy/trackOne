package app.trackone.data.api

import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.InterruptedIOException

/**
 * Applies [RateLimitBackoff] to every Yahoo call, whichever repository makes it. While paused a
 * request never leaves the phone: the caller gets a 429 straight away and falls back to its saved
 * data, as it does for any failed request. Other hosts are left alone.
 *
 * A request that times out is counted too (see [RateLimitBackoff.onTimeout]) and still fails for its caller.
 */
class BackoffInterceptor(private val backoff: RateLimitBackoff) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.url.host.endsWith("yahoo.com")) return chain.proceed(request)

        if (backoff.isPaused()) {
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(429)
                .message("Paused: Yahoo asked us to slow down")
                .body("".toResponseBody(null))
                .build()
        }

        val response = try {
            chain.proceed(request)
        } catch (e: InterruptedIOException) {
            // SocketTimeoutException (connect, read, write) and OkHttp's whole-call timeout are both this.
            backoff.onTimeout()
            throw e
        }
        backoff.onResponse(response.code, response.header("Retry-After"))
        return response
    }
}
