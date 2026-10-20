package app.trackone.data.repository

import android.content.Context
import app.trackone.utils.ChartRange
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The price series behind the Home chart (one per symbol and range), kept on disk so a cold start does
 * not download a month of closes for every holding again: with 95 holdings that was 98 requests, most
 * of what the app still sent after its quotes were de-duplicated. It is only a cache, so it lives in
 * the cache directory, and every failure here (a full disk, a damaged or half-written file) reads as
 * "not saved" and costs one download.
 *
 * One small text file per series: a header line `v1|<fetchedAtMs>|<currency>|<symbol>`, then one
 * `<time key>,<close>` line per point. Written to a temporary file and renamed, so a process killed
 * mid-write leaves the old file, never half of a new one.
 */
@Singleton
class ChartSeriesStore(private val directory: File) {

    @Inject constructor(@ApplicationContext context: Context) : this(File(context.cacheDir, "portfolio_history"))

    class Stored(val fetchedAtMs: Long, val currency: String, val points: List<Pair<Long, Double>>)

    fun load(symbol: String, range: ChartRange): Stored? = runCatching {
        val lines = fileFor(symbol, range).takeIf { it.isFile }?.readLines() ?: return null
        val header = lines.firstOrNull()?.split("|", limit = 4) ?: return null
        if (header.size != 4 || header[0] != VERSION || header[3] != symbol) return null

        val points = lines.drop(1).map { row ->
            val (key, close) = row.split(",")
            key.toLong() to close.toDouble()
        }
        if (points.isEmpty()) null else Stored(header[1].toLong(), header[2], points)
    }.getOrNull()

    fun save(symbol: String, range: ChartRange, stored: Stored) {
        runCatching {
            directory.mkdirs()
            val target = fileFor(symbol, range)
            val temporary = File(directory, target.name + ".tmp")
            temporary.bufferedWriter().use { out ->
                out.write("$VERSION|${stored.fetchedAtMs}|${stored.currency}|$symbol\n")
                stored.points.forEach { (key, close) -> out.write("$key,$close\n") }
            }
            // The file's own date is when the series was downloaded, so [prune] needs no parsing.
            temporary.setLastModified(stored.fetchedAtMs)
            if (!temporary.renameTo(target)) {
                target.delete()
                if (!temporary.renameTo(target)) temporary.delete()
            }
        }
    }

    /** Deletes series downloaded before [olderThanMs]: holdings that were sold leave files nobody reads. */
    fun prune(olderThanMs: Long) {
        runCatching {
            directory.listFiles()?.forEach { if (it.lastModified() < olderThanMs) it.delete() }
        }
    }

    // The name only has to be unique enough: the symbol in the header is checked on load, so two symbols
    // that ever shared a name would evict each other rather than show each other's prices.
    private fun fileFor(symbol: String, range: ChartRange): File {
        val readable = symbol.replace(Regex("[^A-Za-z0-9]"), "_").take(32)
        return File(directory, "$readable-${range.name}-${symbol.hashCode().toUInt().toString(16)}.txt")
    }

    private companion object {
        const val VERSION = "v1"
    }
}
