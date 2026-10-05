package app.trackone.ui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import app.trackone.R
import app.trackone.data.repository.StockRepository
import app.trackone.workers.StockUpdateWorker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class StockWidgetProvider : AppWidgetProvider() {

    @Inject
    lateinit var repository: StockRepository

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { widgetId ->
            CoroutineScope(Dispatchers.IO).launch {
                updateWidget(context, appWidgetManager, widgetId)
            }
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        // Otherwise this map would keep growing with entries for widgets that no longer exist.
        appWidgetIds.forEach { WidgetPrefs.removeWidget(context, it) }
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        StockUpdateWorker.schedule(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        StockUpdateWorker.cancel(context)
    }

    private suspend fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, widgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_stock_list)

        // Label the widget with the watchlist it's actually showing — otherwise two widgets
        // side by side, each scoped to a different watchlist, would be indistinguishable.
        val groupId = WidgetPrefs.getGroupId(context, widgetId)
        val groupName = repository.getWatchlistGroupsSync().firstOrNull { it.id == groupId }?.name
        views.setTextViewText(R.id.widget_header_title, groupName ?: "TrackOne")

        val serviceIntent = Intent(context, StockWidgetService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            data = android.net.Uri.parse("widget://$widgetId")
        }
        views.setRemoteAdapter(R.id.widget_list_view, serviceIntent)
        views.setEmptyView(R.id.widget_list_view, R.id.widget_empty_view)

        val itemClickIntent = Intent(context, StockWidgetActionReceiver::class.java).apply {
            action = StockWidgetActionReceiver.ACTION_WIDGET_ITEM_CLICK
        }
        val itemClickPendingIntent = PendingIntent.getBroadcast(
            context, 0, itemClickIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        views.setPendingIntentTemplate(R.id.widget_list_view, itemClickPendingIntent)

        val refreshIntent = Intent(context, StockWidgetActionReceiver::class.java).apply {
            action = StockWidgetActionReceiver.ACTION_REFRESH_WIDGET
            putExtra(StockWidgetActionReceiver.EXTRA_WIDGET_ID, widgetId)
        }
        val refreshPendingIntent = PendingIntent.getBroadcast(
            context, widgetId + 1000,
            refreshIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_refresh_button, refreshPendingIntent)

        val openAppIntent = Intent(context, app.trackone.ui.main.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            context, widgetId + 2000,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_header, openAppPendingIntent)

        // Empty state → open WidgetConfigActivity so users can add stocks. Passing this
        // widget's id (not just launching bare) is what lets it resolve to *this* widget's
        // assigned watchlist rather than defaulting to the first one.
        val addStocksIntent = Intent(context, app.trackone.ui.config.WidgetConfigActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        }
        val addStocksPendingIntent = PendingIntent.getActivity(
            context, widgetId + 3000,
            addStocksIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_empty_view, addStocksPendingIntent)

        appWidgetManager.updateAppWidget(widgetId, views)
        appWidgetManager.notifyAppWidgetViewDataChanged(widgetId, R.id.widget_list_view)
    }
}
