package com.saurabh.financewidget.ui.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.saurabh.financewidget.R
import com.saurabh.financewidget.data.database.StockEntity
import com.saurabh.financewidget.data.repository.StockRepository
import com.saurabh.financewidget.utils.FormatUtils
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
            repository.getWatchlistSync()
        }
    }

    override fun onDestroy() {}

    override fun getCount(): Int = stocks.size

    override fun getViewAt(position: Int): RemoteViews {
        if (position >= stocks.size) return RemoteViews(context.packageName, R.layout.widget_stock_item)

        val stock = stocks[position]
        val views = RemoteViews(context.packageName, R.layout.widget_stock_item)

        views.setTextViewText(R.id.widget_item_symbol, stock.symbol)
        views.setTextViewText(R.id.widget_item_name, stock.companyName.take(18))
        val priceText = if (stock.symbol.startsWith("^")) {
            FormatUtils.formatIndexPrice(stock.currentPrice, stock.currency)
        } else {
            FormatUtils.formatPrice(stock.currentPrice, stock.currency)
        }
        views.setTextViewText(R.id.widget_item_price, priceText)
        views.setTextViewText(R.id.widget_item_change, FormatUtils.formatChange(stock.change))
        views.setTextViewText(R.id.widget_item_change_percent, FormatUtils.formatChangePercent(stock.changePercent))

        val gainColor = context.getColor(R.color.gain_green)
        val lossColor = context.getColor(R.color.loss_red)
        val changeColor = if (stock.isPositive) gainColor else lossColor

        views.setTextColor(R.id.widget_item_change, changeColor)
        views.setTextColor(R.id.widget_item_change_percent, changeColor)
        views.setInt(R.id.widget_item_indicator, "setImageResource",
            if (stock.isPositive) R.drawable.ic_arrow_up else R.drawable.ic_arrow_down)

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