package app.trackone.data.api

import app.trackone.data.database.StockEntity
import app.trackone.utils.MarketHours
import app.trackone.utils.tradingSession

/**
 * How recent a saved quote has to be for a caller to use it instead of asking Yahoo.
 *
 * @param maxAgeMs a quote younger than this is good enough; 0 means always fetch.
 * @param honourClosedMarket also accept a closing price taken after the close, for as long as the
 * market stays shut. Only for the app opening by itself: a user who pulls to refresh is asking, and
 * estimated session times must not be able to refuse them.
 */
data class Freshness(val maxAgeMs: Long, val honourClosedMarket: Boolean) {
    companion object {
        /** A screen that needs the live price (a stock's detail page, adding a stock). */
        val ALWAYS = Freshness(0L, false)

        /** The user pulled to refresh: only repeats within [RefreshPolicy.WINDOW_MS] are skipped. */
        val PULL = Freshness(RefreshPolicy.WINDOW_MS, false)

        /** The app opening itself (Splash, Home, a worker): five minutes, or the close while shut. */
        val PASSIVE = Freshness(5 * 60_000L, true)

        /** The one rule for a refresh that is asked for or started by the app: [PULL] or [PASSIVE]. */
        fun forRefresh(userRequested: Boolean): Freshness = if (userRequested) PULL else PASSIVE
    }
}

object QuoteFreshness {

    /** After the close, prices are still settling (closing auctions, late prints) for a few minutes. */
    const val SETTLE_MS = 10 * 60_000L

    fun isFresh(stock: StockEntity?, freshness: Freshness, now: Long = System.currentTimeMillis()): Boolean {
        if (stock == null || freshness.maxAgeMs <= 0L) return false
        if (RefreshPolicy.isFresh(stock.lastUpdated, freshness.maxAgeMs, now)) return true
        if (!freshness.honourClosedMarket) return false

        // A quote taken after the close (and settled) is the closing price, and nothing can change
        // it before the next open. Round-the-clock markets (crypto, FX, futures) never close.
        val session = stock.tradingSession() ?: return false
        if (!MarketHours.isExchangeSession(session)) return false
        val settledAt = session.end * 1_000L + SETTLE_MS
        return stock.lastUpdated >= settledAt && now >= stock.lastUpdated && now < MarketHours.nextOpenSec(session) * 1_000L
    }
}
