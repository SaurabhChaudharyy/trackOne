package app.trackone.ui.detail

import android.app.Activity
import android.app.ActivityOptions
import com.google.android.material.transition.platform.MaterialContainerTransform
import com.google.android.material.transition.platform.MaterialContainerTransformSharedElementCallback
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
import app.trackone.R
import app.trackone.data.database.PriceHistoryEntity
import app.trackone.data.database.StockEntity
import app.trackone.databinding.ActivityStockDetailBinding
import app.trackone.utils.AnimationUtils.animateNumberFromZero
import app.trackone.utils.FormatUtils
import app.trackone.utils.Resource
import app.trackone.ui.util.MoneyColor
import app.trackone.ui.util.applyEdgeToEdge
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

    private fun containerTransform(durationMs: Long) = MaterialContainerTransform().apply {
        addTarget(android.R.id.content)
        duration = durationMs
        scrimColor = Color.TRANSPARENT
        setAllContainerColors(getColor(R.color.background))
    }

    companion object {
        const val EXTRA_SYMBOL = "extra_symbol"

        /** Both ends of the container transform share this name. */
        const val SHARED_ELEMENT = "stock_detail_container"

        /**
         * Pass the tapped row/card as [sharedView] and it grows into this screen (and shrinks back on
         * return) — Material's container transform, the pattern for list item -> detail.
         */
        fun start(context: Context, symbol: String, sharedView: View? = null) {
            val intent = Intent(context, StockDetailActivity::class.java).putExtra(EXTRA_SYMBOL, symbol)
            val activity = context as? Activity
            if (activity != null && sharedView != null) {
                sharedView.transitionName = SHARED_ELEMENT
                val options = ActivityOptions.makeSceneTransitionAnimation(activity, sharedView, SHARED_ELEMENT)
                context.startActivity(intent, options.toBundle())
            } else {
                context.startActivity(intent)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Container-transform end of the shared element: must be set up before super.onCreate.
        findViewById<View>(android.R.id.content).transitionName = SHARED_ELEMENT
        setEnterSharedElementCallback(MaterialContainerTransformSharedElementCallback())
        window.sharedElementEnterTransition = containerTransform(durationMs = 300L)
        window.sharedElementReturnTransition = containerTransform(durationMs = 250L)

        super.onCreate(savedInstanceState)
        binding = ActivityStockDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyEdgeToEdge(topInsetView = binding.toolbar, bottomInsetView = null)

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

        // Clean line — like the Coinbase screenshot. Sits directly on the chart's
        // (flipping) background, so its ink must flip with the theme too.
        val lineColor = getColor(R.color.text_primary)
        // Resolved out here on purpose: inside LineDataSet.apply {}, getColor(Int) is the data
        // set's own getColor(index), which returns the line colour, not a colour resource.
        val density = resources.displayMetrics.density
        val dotFill = app.trackone.ui.util.DotGridDrawable(
            color     = getColor(R.color.chart_dots),
            spacingPx = 5f * density,
            radiusPx  = 0.9f * density
        )

        val entries = points.mapIndexed { i, y -> Entry(i.toFloat(), y) }

        val dataSet = LineDataSet(entries, "Price").apply {
            color = lineColor
            setDrawCircles(false)
            setDrawCircleHole(false)
            setDrawValues(false)
            lineWidth = 2f
            mode = LineDataSet.Mode.CUBIC_BEZIER
            cubicIntensity = 0.12f

            // The same dotted ink fill as the Home chart, so the two charts read as one design.
            setDrawFilled(true)
            fillDrawable = dotFill

            // Crosshair uses the line's ink so it reads in both themes
            highLightColor = lineColor
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
        val inkColor = getColor(R.color.ink)
        val dp = resources.displayMetrics.density

        TIMEFRAME_OPTIONS.keys.forEach { label ->
            val chip = Chip(this).apply {
                text = label
                isCheckable = true
                isCheckedIconVisible = false
                // A fully rounded ink pill when checked, bold labels: same as the Home range buttons.
                chipCornerRadius = 999f * dp
                // A Chip draws its label from its text appearance; set before the colours below.
                setTextAppearance(R.style.TextAppearance_App_RangeChip)
                chipStrokeWidth = 0f
                chipBackgroundColor = android.content.res.ColorStateList(
                    arrayOf(
                        intArrayOf(android.R.attr.state_checked),
                        intArrayOf()
                    ),
                    intArrayOf(
                        inkColor,
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
                            // Checked chip is an ink block (flips with the theme); its label is the inverse.
                            getColor(R.color.on_ink),
                            getColor(R.color.text_secondary)
                        )
                    )
                )

                chipMinHeight = 36f * dp
                chipStartPadding = 6f * dp
                chipEndPadding = 6f * dp
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
        spreadTimeframeChips()
    }

    /**
     * Spreads the chips across the full row (1D at the left edge, 5Y at the right), as on Home.
     * ChipGroup has no weights, so the gap is measured once the chips have their widths.
     */
    private fun spreadTimeframeChips() {
        val group = binding.timeframeChipGroup
        group.isSingleLine = true
        group.post {
            val chips = (0 until group.childCount).map { group.getChildAt(it) }
            if (chips.size < 2) return@post
            val free = group.width - group.paddingStart - group.paddingEnd - chips.sumOf { it.width }
            group.chipSpacingHorizontal = (free / (chips.size - 1)).coerceAtLeast(0)
        }
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

        // The day's move is coloured text; money direction never gets a filled pill.
        binding.tvDetailChange.setTextColor(getColor(MoneyColor.forChange(stock.change)))
        binding.tvDetailChange.background = null

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
