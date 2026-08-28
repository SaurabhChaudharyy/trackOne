package app.trackone.ui.widget

import android.content.Context
import app.trackone.data.repository.StockRepository

/**
 * Persists which watchlist group each home-screen widget instance is showing. A widget is
 * otherwise a stateless RemoteViews host with no memory of its own between updates — this
 * SharedPreferences map (keyed by Android's per-instance appWidgetId) is the only place that
 * "this widget = this watchlist" association lives, letting different widget instances show
 * different watchlists at once.
 */
object WidgetPrefs {
    private const val PREFS_NAME = "widget_group_prefs"
    private const val KEY_PREFIX = "group_for_widget_"

    /** Falls back to the default watchlist for a widget that's never been assigned one
     *  (e.g. one placed before this per-widget setting existed). */
    fun getGroupId(context: Context, widgetId: Int): Long {
        val stored = prefs(context).getLong(KEY_PREFIX + widgetId, -1L)
        return if (stored > 0) stored else StockRepository.DEFAULT_GROUP_ID
    }

    fun setGroupId(context: Context, widgetId: Int, groupId: Long) {
        prefs(context).edit().putLong(KEY_PREFIX + widgetId, groupId).apply()
    }

    /** Called when a widget instance is removed from the home screen, so this map doesn't
     *  accumulate entries for widgets that no longer exist. */
    fun removeWidget(context: Context, widgetId: Int) {
        prefs(context).edit().remove(KEY_PREFIX + widgetId).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
