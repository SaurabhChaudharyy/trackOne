package app.trackone.utils

import app.trackone.data.database.StockEntity

/**
 * The latest trading session Yahoo reported for a symbol, as saved with its quote. Times are epoch
 * seconds (0 = unknown); [fetchedAt] is when the quote was taken, in epoch milliseconds.
 */
data class TradingSession(
    val preStart: Long,
    val start: Long,
    val end: Long,
    val postEnd: Long,
    val gmtOffset: Int,
    val fetchedAt: Long
) {
    val isKnown: Boolean get() = start > 0L && end > start
    val hasPreMarket: Boolean get() = isKnown && preStart in 1 until start
    val hasAfterHours: Boolean get() = isKnown && postEnd > end
}

fun StockEntity.tradingSession(): TradingSession? =
    TradingSession(sessionPreStart, sessionStart, sessionEnd, sessionPostEnd, sessionGmtOffset, lastUpdated)
        .takeIf { it.isKnown }

/**
 * Whether a market is open, from the session times Yahoo sends with every quote rather than fixed
 * weekday hours. Yahoo reports the *latest* session that ran, so a weekend or a holiday simply shows
 * as "now is outside it" — which the fixed hours in [MarketUtils] cannot know (they say Open all
 * through a weekday holiday). The fixed hours remain the fallback when nothing has been fetched yet.
 */
object MarketHours {

    enum class State { OPEN, CLOSED, UNKNOWN }

    /** A quote this fresh is taken at its word about which session is current. */
    const val AUTHORITATIVE_MS = 15 * 60_000L

    /** Longer than this and a window is a round-the-clock market (crypto, FX, futures), not an exchange session. */
    private const val MAX_EXCHANGE_SESSION_SEC = 20 * 3_600L

    private const val DAY_SEC = 86_400L

    fun state(session: TradingSession?, nowMs: Long): State {
        if (session == null || !session.isKnown) return State.UNKNOWN
        val nowSec = nowMs / 1_000L

        if (nowSec in session.start until session.end) return State.OPEN
        // Outside the session. A recent quote that still shows this session means nothing newer has
        // started (a holiday, or before the open); an old one can only vouch until the next open.
        if (nowMs - session.fetchedAt < AUTHORITATIVE_MS) return State.CLOSED
        return if (nowSec < nextOpenSec(session)) State.CLOSED else State.UNKNOWN
    }

    /**
     * An estimate of the next session's start: the same time tomorrow, or Monday after a Friday. Right
     * for most markets; a holiday or a Friday–Saturday weekend makes it early, which costs one
     * refetch and never a wrong price. The weekday is the exchange's own (from [TradingSession.gmtOffset]).
     */
    fun nextOpenSec(session: TradingSession): Long {
        val localDay = Math.floorDiv(session.start + session.gmtOffset, DAY_SEC)
        val weekday = Math.floorMod(localDay + 4, 7L)          // 1970-01-01 was a Thursday; 0 = Sunday
        val days = when (weekday) { 5L -> 3L; 6L -> 2L; else -> 1L }
        return session.start + days * DAY_SEC
    }

    /** True for a normal exchange session; false for markets that trade (almost) around the clock. */
    fun isExchangeSession(session: TradingSession): Boolean =
        session.isKnown && session.end - session.start < MAX_EXCHANGE_SESSION_SEC
}
