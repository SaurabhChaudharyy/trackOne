package com.saurabh.financewidget.ui.detail

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.listener.OnChartValueSelectedListener
import com.google.android.material.chip.Chip
import com.saurabh.financewidget.R
import com.saurabh.financewidget.data.database.PriceHistoryEntity
import com.saurabh.financewidget.data.database.StockEntity
import com.saurabh.financewidget.databinding.ActivityStockDetailBinding
import com.saurabh.financewidget.utils.AnimationUtils.animateNumberFromZero
import com.saurabh.financewidget.utils.FormatUtils
import com.saurabh.financewidget.utils.Resource
import dagger.hilt.android.AndroidEntryPoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@AndroidEntryPoint
class StockDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStockDetailBinding
    private val viewModel: StockDetailViewModel by viewModels()
    private var currentResolution = "D"
    private var chartTimestamps: List<Long> = emptyList()
    private var hasPriceAnimated = false

    companion object {
        const val EXTRA_SYMBOL = "extra_symbol"

        fun start(context: Context, symbol: String) {
            Intent(context, StockDetailActivity::class.java).also {
                it.putExtra(EXTRA_SYMBOL, symbol)
                context.startActivity(it)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStockDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val symbol = intent.getStringExtra(EXTRA_SYMBOL) ?: run {
            finish()
            return
        }

        setupToolbar()
        setupChart()
        setupTimeframeChips()
        observeViewModel()

        viewModel.loadStock(symbol)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val symbol = intent.getStringExtra(EXTRA_SYMBOL) ?: return
        
        (binding.timeframeChipGroup.getChildAt(0) as? Chip)?.isChecked = true
        currentResolution = "1D"
        
        viewModel.loadStock(symbol)
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = ""
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
    }

    private fun setupChart() {
        binding.lineChart.apply {
            description.isEnabled = false
            legend.isEnabled = false
            setBackgroundColor(Color.TRANSPARENT)
            setNoDataText("Loading chart data...")
            setNoDataTextColor(getColor(R.color.text_tertiary))

            setTouchEnabled(true)
            isDragEnabled = true
            setScaleEnabled(false)
            setPinchZoom(false)
            isDoubleTapToZoomEnabled = false
            isHighlightPerTapEnabled = true
            isHighlightPerDragEnabled = true

            xAxis.isEnabled = false
            axisLeft.isEnabled = false
            axisRight.isEnabled = false

            setDrawGridBackground(false)
            setDrawBorders(false)
            extraBottomOffset = 8f
            minOffset = 0f

            setOnChartValueSelectedListener(object : OnChartValueSelectedListener {
                override fun onValueSelected(e: Entry, h: Highlight) {
                    val stock = viewModel.stock.value ?: return
                    val label = FormatUtils.formatPrice(e.y.toDouble(), stock.currency)
                    description.isEnabled = true
                    description.text = label
                    description.textColor = getColor(R.color.text_primary)
                    description.textSize = 12f
                    invalidate()
                }
                override fun onNothingSelected() {
                    description.isEnabled = false
                    invalidate()
                }
            })
        }
    }

    private fun displayChart(history: List<PriceHistoryEntity>) {
        binding.chartProgress.visibility = View.GONE

        if (history.isEmpty()) {
            binding.lineChart.setNoDataText("No chart data available")
            binding.lineChart.clear()
            return
        }

        val points = history.map { it.close.toFloat() }
        chartTimestamps = history.map { it.timestamp }

        // Clean black line — like the Coinbase screenshot
        val lineColor = Color.parseColor("#09090B")

        val entries = points.mapIndexed { i, y -> Entry(i.toFloat(), y) }

        val dataSet = LineDataSet(entries, "Price").apply {
            color = lineColor
            setDrawCircles(false)
            setDrawCircleHole(false)
            setDrawValues(false)
            lineWidth = 2f
            mode = LineDataSet.Mode.CUBIC_BEZIER
            cubicIntensity = 0.12f

            // No fill — clean line only
            setDrawFilled(false)

            // Neon yellow highlight crosshair
            highLightColor = getColor(R.color.neon_highlight)
            highlightLineWidth = 1.5f
            enableDashedHighlightLine(6f, 3f, 0f)
            setDrawHorizontalHighlightIndicator(false)
        }

        binding.lineChart.apply {
            data = LineData(dataSet)
            animateX(600)
            invalidate()
        }
    }

    private fun setupTimeframeChips() {
        val neonColor = getColor(R.color.neon_highlight)

        TIMEFRAME_OPTIONS.keys.forEach { label ->
            val chip = Chip(this).apply {
                text = label
                isCheckable = true
                chipCornerRadius = 0f
                chipStrokeWidth = 0f
                chipBackgroundColor = android.content.res.ColorStateList(
                    arrayOf(
                        intArrayOf(android.R.attr.state_checked),
                        intArrayOf()
                    ),
                    intArrayOf(
                        neonColor,
                        Color.TRANSPARENT
                    )
                )

                setTextColor(
                    android.content.res.ColorStateList(
                        arrayOf(
                            intArrayOf(android.R.attr.state_checked),
                            intArrayOf()
                        ),
                        intArrayOf(
                            getColor(R.color.text_primary),
                            getColor(R.color.text_tertiary)
                        )
                    )
                )

                textSize = 13f
                chipMinHeight = 32f
                chipStartPadding = 10f
                chipEndPadding = 10f
                tag = label
            }
            binding.timeframeChipGroup.addView(chip)
        }

        binding.timeframeChipGroup.setOnCheckedStateChangeListener { group, _ ->
            val checkedChip = group.findViewById<Chip>(group.checkedChipId)
            val label = checkedChip?.tag as? String ?: "1D"
            currentResolution = label
            viewModel.loadPriceHistory(label)
        }

        (binding.timeframeChipGroup.getChildAt(0) as? Chip)?.isChecked = true
    }

    private fun observeViewModel() {
        viewModel.stock.observe(this) { stock ->
            stock?.let { displayStockData(it) }
        }

        viewModel.priceHistory.observe(this) { resource ->
            when (resource) {
                is Resource.Success -> displayChart(resource.data)
                is Resource.Loading -> binding.chartProgress.visibility = View.VISIBLE
                is Resource.Error -> {
                    binding.chartProgress.visibility = View.GONE
                    binding.lineChart.setNoDataText("Chart data unavailable")
                    binding.lineChart.clear()
                }
            }
        }

        viewModel.isLoading.observe(this) { isLoading ->
            binding.loadingOverlay.visibility = if (isLoading) View.VISIBLE else View.GONE
        }
    }

    private fun displayStockData(stock: StockEntity) {
        val isIndex = stock.symbol.startsWith("^")
        binding.tvDetailSymbol.text = stock.symbol.removePrefix("^")
        binding.tvDetailCompanyName.text = stock.companyName

        // Animated number ticker — only on first load; instant update on subsequent refreshes
        val priceFormatter: (Double) -> String = { price ->
            if (isIndex) FormatUtils.formatIndexPrice(price, stock.currency)
            else FormatUtils.formatPrice(price, stock.currency)
        }
        if (!hasPriceAnimated) {
            binding.tvDetailPrice.animateNumberFromZero(stock.currentPrice, format = priceFormatter)
            hasPriceAnimated = true
        } else {
            binding.tvDetailPrice.text = priceFormatter(stock.currentPrice)
        }

        // Format change with arrow indicator like Coinbase
        val arrow = if (stock.isPositive) "↗" else "↘"
        binding.tvDetailChange.text = "$arrow ${FormatUtils.formatChange(stock.change)} · ${FormatUtils.formatChangePercent(stock.changePercent)}"

        // Neon for positive, red for negative
        val primaryColor = getColor(R.color.text_primary)
        binding.tvDetailChange.setTextColor(primaryColor)
        binding.tvDetailChange.setBackgroundResource(
            if (stock.isPositive) R.drawable.bg_gain_pill else R.drawable.bg_loss_pill
        )

        binding.tvStatOpen.text = if (stock.openPrice != 0.0)
            (if (isIndex) FormatUtils.formatIndexPrice(stock.openPrice, stock.currency)
             else FormatUtils.formatPrice(stock.openPrice, stock.currency)) else "—"

        val displayHigh = maxOf(stock.highPrice, stock.openPrice)
        binding.tvStatHigh.text = if (isIndex)
            FormatUtils.formatIndexPrice(displayHigh, stock.currency)
        else
            FormatUtils.formatPrice(displayHigh, stock.currency)
        binding.tvStatLow.text = if (isIndex)
            FormatUtils.formatIndexPrice(stock.lowPrice, stock.currency)
        else
            FormatUtils.formatPrice(stock.lowPrice, stock.currency)
        binding.tvStatPrevClose.text = if (stock.previousClose != 0.0)
            (if (isIndex) FormatUtils.formatIndexPrice(stock.previousClose, stock.currency)
             else FormatUtils.formatPrice(stock.previousClose, stock.currency)) else "—"

        if (stock.industry.isNotEmpty()) {
            binding.tvDetailIndustry.text = " · ${stock.industry}"
            binding.tvDetailIndustry.visibility = View.VISIBLE
        }

        if (stock.exchange.isNotEmpty()) {
            binding.tvDetailExchange.text = " · ${stock.exchange}"
            binding.tvDetailExchange.visibility = View.VISIBLE
        }

        binding.tvLastUpdatedDetail.text = "Updated ${FormatUtils.formatLastUpdated(stock.lastUpdated)}"
        supportActionBar?.title = stock.symbol.removePrefix("^")
    }
}
