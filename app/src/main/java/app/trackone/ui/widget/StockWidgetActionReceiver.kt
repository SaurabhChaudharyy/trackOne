package app.trackone.ui.widget

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.trackone.data.repository.StockRepository
import app.trackone.ui.detail.StockDetailActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * What the widget's own buttons do: refresh, and open a stock. These used to be handled by the widget
 * provider, which has to be exported so the launcher can update it, so any app could send them too.
 * This receiver is not exported: only the widget's own PendingIntents reach it.
 */
@AndroidEntryPoint
class StockWidgetActionReceiver : BroadcastReceiver() {

    @Inject
    lateinit var repository: StockRepository

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_REFRESH_WIDGET -> {
                val widgetId = intent.getIntExtra(EXTRA_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                // goAsync keeps the process alive until the refresh finishes; an unanchored coroutine in a
                // receiver can be killed halfway.
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        repository.refreshWatchlistStocks()
                        if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                            context.sendBroadcast(
                                Intent(context, StockWidgetProvider::class.java).apply {
                                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(widgetId))
                                }
                            )
                        }
                    } finally {
                        pending.finish()
                    }
                }
            }
            ACTION_WIDGET_ITEM_CLICK -> {
                val symbol = intent.getStringExtra(EXTRA_SYMBOL) ?: return
                context.startActivity(
                    Intent(context, StockDetailActivity::class.java).apply {
                        putExtra(StockDetailActivity.EXTRA_SYMBOL, symbol)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                )
            }
        }
    }

    companion object {
        const val ACTION_REFRESH_WIDGET = "app.trackone.ACTION_REFRESH_WIDGET"
        const val ACTION_WIDGET_ITEM_CLICK = "app.trackone.ACTION_WIDGET_ITEM_CLICK"
        const val EXTRA_SYMBOL = "extra_symbol"
        const val EXTRA_WIDGET_ID = "extra_widget_id"
    }
}
