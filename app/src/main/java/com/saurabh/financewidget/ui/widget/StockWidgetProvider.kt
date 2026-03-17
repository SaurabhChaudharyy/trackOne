package com.saurabh.financewidget.ui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.saurabh.financewidget.R
import com.saurabh.financewidget.data.repository.StockRepository
import com.saurabh.financewidget.ui.detail.StockDetailActivity
import com.saurabh.financewidget.workers.StockUpdateWorker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class StockWidgetProvider : AppWidgetProvider() {

    @Inject
    lateinit var repository: StockRepository

    companion object {
        const val ACTION_REFRESH_WIDGET = "com.saurabh.financewidget.ACTION_REFRESH_WIDGET"
        const val ACTION_WIDGET_ITEM_CLICK = "com.saurabh.financewidget.ACTION_WIDGET_ITEM_CLICK"
        const val EXTRA_SYMBOL = "extra_symbol"
        const val EXTRA_WIDGET_ID = "extra_widget_id"
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { widgetId ->
            updateWidget(context, appWidgetManager, widgetId)
        }
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        StockUpdateWorker.schedule(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        StockUpdateWorker.cancel(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_REFRESH_WIDGET -> {
                val widgetId = intent.getIntExtra(EXTRA_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                val appWidgetManager = AppWidgetManager.getInstance(context)

                CoroutineScope(Dispatchers.IO).launch {
                    repository.refreshWatchlistStocks()
                    if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                        updateWidget(context, appWidgetManager, widgetId)
                    }
                }
            }
            ACTION_WIDGET_ITEM_CLICK -> {
                val symbol = intent.getStringExtra(EXTRA_SYMBOL) ?: return
                val detailIntent = Intent(context, StockDetailActivity::class.java).apply {
                    putExtra(StockDetailActivity.EXTRA_SYMBOL, symbol)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                context.startActivity(detailIntent)
            }
        }
    }

    private fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, widgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_stock_list)

        val serviceIntent = Intent(context, StockWidgetService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            data = android.net.Uri.parse("widget://$widgetId")
        }
        views.setRemoteAdapter(R.id.widget_list_view, serviceIntent)
        views.setEmptyView(R.id.widget_list_view, R.id.widget_empty_view)

        val itemClickIntent = Intent(context, StockWidgetProvider::class.java).apply {
            action = ACTION_WIDGET_ITEM_CLICK
        }
        val itemClickPendingIntent = PendingIntent.getBroadcast(
            context, 0, itemClickIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        views.setPendingIntentTemplate(R.id.widget_list_view, itemClickPendingIntent)

        val refreshIntent = Intent(context, StockWidgetProvider::class.java).apply {
            action = ACTION_REFRESH_WIDGET
            putExtra(EXTRA_WIDGET_ID, widgetId)
        }
        val refreshPendingIntent = PendingIntent.getBroadcast(
            context, widgetId + 1000,
            refreshIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_refresh_button, refreshPendingIntent)

        val openAppIntent = Intent(context, com.saurabh.financewidget.ui.main.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            context, widgetId + 2000,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_header, openAppPendingIntent)

        appWidgetManager.updateAppWidget(widgetId, views)
        appWidgetManager.notifyAppWidgetViewDataChanged(widgetId, R.id.widget_list_view)
    }
}
