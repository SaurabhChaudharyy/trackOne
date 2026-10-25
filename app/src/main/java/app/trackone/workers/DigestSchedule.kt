package app.trackone.workers

import app.trackone.data.database.AssetType
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** An index a market digest reports: [symbol] as Yahoo knows it, [label] as the notification shows it. */
internal data class DigestIndex(val symbol: String, val label: String)

/**
 * A market whose close the daily digest follows. The digest goes out [DIGEST_DELAY] after the
 * close, worked out in the market's own zone, so the time follows its daylight saving: the US digest
 * is an hour later on a phone in India for part of the year, and still lands an hour after the bell.
 */
internal enum class DigestMarket(
    val key: String,
    /** "Indian" / "US", for "Indian Market Closed". */
    val adjective: String,
    private val zone: ZoneId,
    private val close: LocalTime,
    /** The holdings this market's digest reports on. */
    val assetType: AssetType,
    val indices: List<DigestIndex>
) {
    INDIA(
        "india", "Indian", ZoneId.of("Asia/Kolkata"), LocalTime.of(15, 30), AssetType.STOCK_IN,
        listOf(DigestIndex("^NSEI", "Nifty 50"), DigestIndex("^BSESN", "Sensex"))
    ),
    US(
        "us", "US", ZoneId.of("America/New_York"), LocalTime.of(16, 0), AssetType.STOCK_US,
        listOf(DigestIndex("^GSPC", "S&P 500"), DigestIndex("^IXIC", "Nasdaq"))
    );

    val workName: String get() = "daily_digest_$key"

    /**
     * The first digest time after [after]: [DIGEST_DELAY] past the close, on a weekday in the market's
     * own zone. Exchange holidays are not known here; the worker skips those once it sees the quotes.
     */
    fun nextDigest(after: Instant): ZonedDateTime {
        val digestTime = close.plus(DIGEST_DELAY)
        var date = after.atZone(zone).toLocalDate()
        while (true) {
            val candidate = ZonedDateTime.of(date, digestTime, zone)
            if (candidate.toInstant().isAfter(after) && date.dayOfWeek !in WEEKEND) return candidate
            date = date.plusDays(1)
        }
    }

    companion object {
        val DIGEST_DELAY: Duration = Duration.ofHours(1)
        private val WEEKEND = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

        fun fromKey(key: String?): DigestMarket? = entries.firstOrNull { it.key == key }
    }
}

/** How long after a session ends it still counts as "today's": past this, no session ran today. */
private const val MAX_SESSION_AGE_SEC = 12 * 3_600L

/**
 * Whether a market traded today, from the end of the latest regular session Yahoo reports with a quote.
 * Yahoo answers with the latest session that ran, so on an exchange holiday it is yesterday's and the
 * "move" would be a day-old one dressed up as today's. An unknown end (0), or one still in the future
 * (Yahoo already looking ahead to the next session), says nothing, so the digest goes ahead.
 */
internal fun marketTradedToday(sessionEndSec: Long, nowSec: Long): Boolean =
    sessionEndSec <= 0L || nowSec - sessionEndSec <= MAX_SESSION_AGE_SEC

/** The time of day [market]'s next digest lands on a phone in [zone], as "4:30 PM". */
internal fun DigestMarket.nextDigestTimeLabel(now: Instant, zone: ZoneId, locale: Locale): String =
    DateTimeFormatter.ofPattern("h:mm a", locale).format(nextDigest(now).withZoneSameInstant(zone))
