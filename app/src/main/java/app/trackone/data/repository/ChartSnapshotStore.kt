package app.trackone.data.repository

import android.content.Context
import androidx.core.content.edit
import app.trackone.ui.home.PortfolioChartPoint
import app.trackone.utils.ChartRange
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The last Home chart drawn for each range, kept on disk so a cold start can draw it at once
 * instead of a grey placeholder while a month of prices downloads for every holding.
 */
@Singleton
class ChartSnapshotStore @Inject constructor(@ApplicationContext context: Context) {

    private val prefs = context.getSharedPreferences("chart_snapshots", Context.MODE_PRIVATE)

    fun load(range: ChartRange): List<PortfolioChartPoint>? =
        prefs.getString(range.name, null)?.let(::decode)?.takeIf { it.size >= 2 }

    fun save(range: ChartRange, points: List<PortfolioChartPoint>) {
        prefs.edit { putString(range.name, encode(points)) }
    }

    companion object {
        fun encode(points: List<PortfolioChartPoint>): String =
            points.joinToString(";") { "${it.timestamp},${it.invested},${it.current}" }

        /** Null when anything in [text] doesn't parse, so a damaged snapshot is just skipped. */
        fun decode(text: String): List<PortfolioChartPoint>? = runCatching {
            text.split(";").map { row ->
                val (t, i, c) = row.split(",")
                PortfolioChartPoint(t.toLong(), i.toDouble(), c.toDouble())
            }
        }.getOrNull()
    }
}
