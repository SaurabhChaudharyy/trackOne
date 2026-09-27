package app.trackone.ui.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import app.trackone.R
import app.trackone.data.database.StockEntity
import app.trackone.data.repository.StockRepository
import app.trackone.ui.util.MoneyColor
import app.trackone.utils.FormatUtils
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@AndroidEntryPoint
class StockWidgetService : RemoteViewsService() {

    @Inject
    lateinit var repository: StockRepository

    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        return StockRemoteViewsFactory(applicationContext, intent, repository)
    }
}

class StockRemoteViewsFactory(
    private val context: Context,
    intent: Intent,
    private val repository: StockRepository
) : RemoteViewsService.RemoteViewsFactory {

    private val widgetId = intent.getIntExtra(
        AppWidgetManager.EXTRA_APPWIDGET_ID,
        AppWidgetManager.INVALID_APPWIDGET_ID
    )
    private var stocks: List<StockEntity> = emptyList()

    override fun onCreate() {
        loadData()
    }

    override fun onDataSetChanged() {
        loadData()
    }

    private fun loadData() {
        stocks = runBlocking {
            val groupId = WidgetPrefs.getGroupId(context, widgetId)
            repository.getWatchlistStocksSyncByGroup(groupId)
        }
    }

    override fun onDestroy() {}

    override fun getCount(): Int = stocks.size

    override fun getViewAt(position: Int): RemoteViews {
        if (position >= stocks.size) return RemoteViews(context.packageName, R.layout.widget_stock_item)

        val stock = stocks[position]
        val views = RemoteViews(context.packageName, R.layout.widget_stock_item)

        views.setTextViewText(R.id.widget_item_symbol, stock.symbol.removePrefix("^"))
        views.setTextViewText(R.id.widget_item_name, stock.companyName.take(18))
        val priceText = if (stock.symbol.startsWith("^")) {
            FormatUtils.formatIndexPrice(stock.currentPrice, stock.currency)
        } else {
            FormatUtils.formatPrice(stock.currentPrice, stock.currency)
        }
        views.setTextViewText(R.id.widget_item_price, priceText)

        val changePercentText = FormatUtils.formatChangePercent(stock.changePercent)

        // Show exactly one of the gain / loss / flat views; their colours are baked into the layout.
        val shown = when (MoneyColor.forChange(stock.changePercent)) {
            R.color.gain -> R.id.widget_item_gain
            R.color.loss -> R.id.widget_item_loss
            else -> R.id.widget_item_flat
        }
        for (id in intArrayOf(R.id.widget_item_gain, R.id.widget_item_loss, R.id.widget_item_flat)) {
            views.setViewVisibility(id, if (id == shown) android.view.View.VISIBLE else android.view.View.GONE)
        }
        views.setTextViewText(shown, changePercentText)

        val clickIntent = Intent().apply {
            putExtra(StockWidgetProvider.EXTRA_SYMBOL, stock.symbol)
        }
        views.setOnClickFillInIntent(R.id.widget_item_root, clickIntent)

        return views
    }

    override fun getLoadingView(): RemoteViews {
        return RemoteViews(context.packageName, R.layout.widget_stock_item_loading)
    }

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long =
        stocks.getOrNull(position)?.symbol?.hashCode()?.toLong() ?: position.toLong()

    override fun hasStableIds(): Boolean = true
}