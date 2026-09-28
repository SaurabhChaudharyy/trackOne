package app.trackone.ui.home

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.google.android.material.bottomnavigation.BottomNavigationView
import app.trackone.R
import app.trackone.data.database.StockEntity
import app.trackone.databinding.FragmentHomeBinding
import app.trackone.ui.detail.StockDetailActivity
import app.trackone.ui.util.MoneyColor
import app.trackone.utils.AnimationUtils.animateNumberFromZero
import app.trackone.utils.ChartAxis
import app.trackone.utils.ChartRange
import app.trackone.utils.FormatUtils
import app.trackone.utils.MarketUtils
import app.trackone.utils.Resource
import dagger.hilt.android.AndroidEntryPoint
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

@AndroidEntryPoint
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val viewModel: HomeViewModel by viewModels()


    // Track whether prices have animated yet (only animate on first delivery)
    private var niftyAnimated   = false
    private var sensexAnimated  = false
    private var sp500Animated   = false
    private var nasdaqAnimated  = false
    private var portfolioAnimated = false

    // Chart state
    private var allChartPoints: List<PortfolioChartPoint> = emptyList()
    private var scrubbedIndex = -1
    private var currentPortfolioTotal: Double? = null
    /** Once a chart has shown, the range chips stay put even when a range has no data, so the
     *  person can always switch away from it. */
    private var chartEverShown = false
    /** Mover card that launched the stock detail screen, so a rebuilt card keeps its transition name. */
    private var sharedMoverSymbol: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupDateTime()
        setupPortfolioChart()
        setupRangeChips()
        setupMarketStatusChips()
        setupIndexCardClicks()
        setupPortfolioCardClick()
        setupSwipeRefresh()
        observeViewModel()
    }

    override fun onResume() {
        super.onResume()
        updateMarketStatus()
    }

    // ── Date ─────────────────────────────────────────────────────────────

    private fun setupDateTime() {
        val dateFmt = SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault())
        binding.tvDate.text = dateFmt.format(Calendar.getInstance().time)
    }

    // ── Market Status ────────────────────────────────────────────────────

    private fun setupMarketStatusChips() {
        binding.chipUsMarket.setOnClickListener { showMarketHoursDialog(Market.US) }
        binding.chipIndiaMarket.setOnClickListener { showMarketHoursDialog(Market.INDIA) }
    }

    private fun updateMarketStatus() {
        if (_binding == null) return
        val usOpen    = MarketUtils.isUsMarketOpen()
        val indiaOpen = MarketUtils.isIndiaMarketOpen()

        // US chip — ink pill for Open (flips with the theme), gray pill for Closed
        binding.tvUsMarketStatus.text = if (usOpen) "Open" else "Closed"
        binding.tvUsMarketStatus.setTextColor(
            requireContext().getColor(if (usOpen) R.color.on_ink else R.color.text_tertiary)
        )
        binding.tvUsLabel.setTextColor(
            requireContext().getColor(if (usOpen) R.color.on_ink else R.color.text_tertiary)
        )
        binding.chipUsMarket.background = ContextCompat.getDrawable(
            requireContext(),
            if (usOpen) R.drawable.bg_market_chip_open else R.drawable.bg_market_chip
        )

        // India chip — ink pill for Open (flips with the theme), gray pill for Closed
        binding.tvIndiaMarketStatus.text = if (indiaOpen) "Open" else "Closed"
        binding.tvIndiaMarketStatus.setTextColor(
            requireContext().getColor(if (indiaOpen) R.color.on_ink else R.color.text_tertiary)
        )
        binding.tvIndiaLabel.setTextColor(
            requireContext().getColor(if (indiaOpen) R.color.on_ink else R.color.text_tertiary)
        )
        binding.chipIndiaMarket.background = ContextCompat.getDrawable(
            requireContext(),
            if (indiaOpen) R.drawable.bg_market_chip_open else R.drawable.bg_market_chip
        )
    }

    // ── Index card click → StockDetailActivity ──────────────────────────

    private fun setupIndexCardClicks() {
        binding.cardNifty.setOnClickListener  { StockDetailActivity.start(requireActivity(), "^NSEI", it) }
        binding.cardSensex.setOnClickListener { StockDetailActivity.start(requireActivity(), "^BSESN", it) }
        binding.cardSp500.setOnClickListener  { StockDetailActivity.start(requireActivity(), "^GSPC", it) }
        binding.cardNasdaq.setOnClickListener { StockDetailActivity.start(requireActivity(), "^IXIC", it) }
    }

    // ── Portfolio card → NetWorth tab ───────────────────────────────────

    private fun setupPortfolioCardClick() {
        binding.cardPortfolioSummary.setOnClickListener {
            requireActivity()
                .findViewById<BottomNavigationView>(R.id.bottom_nav)
                ?.selectedItemId = R.id.nav_networth
        }
    }

    private fun setupSwipeRefresh() {
        // Constant black on purpose: the spinner's disc is always light, so ink (white in dark
        // mode) would vanish into it.
        binding.swipeRefreshHome.setColorSchemeColors(
            requireContext().getColor(R.color.primary)
        )
        binding.swipeRefreshHome.setOnRefreshListener {
            // Reset animation flags so numbers re-animate on refresh
            niftyAnimated    = false
            sensexAnimated   = false
            sp500Animated    = false
            nasdaqAnimated   = false
            portfolioAnimated = false
            viewModel.fetchAll()
        }
    }



    // ── Observe ViewModel ────────────────────────────────────────────────

    private fun observeViewModel() {
        viewModel.nifty.observe(viewLifecycleOwner)  { res -> applyIndex(res, Index.NIFTY) }
        viewModel.sensex.observe(viewLifecycleOwner) { res -> applyIndex(res, Index.SENSEX) }
        viewModel.sp500.observe(viewLifecycleOwner)  { res -> applyIndex(res, Index.SP500) }
        viewModel.nasdaq.observe(viewLifecycleOwner) { res -> applyIndex(res, Index.NASDAQ) }

        viewModel.topMovers.observe(viewLifecycleOwner) { movers -> applyTopMovers(movers) }

        viewModel.portfolioSummary.observe(viewLifecycleOwner) { summary ->
            applyPortfolioSummary(summary)
        }

        viewModel.chartRange.observe(viewLifecycleOwner) { styleRangeChips(it) }

        viewModel.chartLoading.observe(viewLifecycleOwner) { loading ->
            // First load: reserve the chart's space with a pulsing placeholder instead of leaving a
            // gap that the chart later pushes everything below it out of. Later loads (range
            // switches) keep the old chart on screen and show the small spinner over it.
            val firstLoad = loading && !chartEverShown
            if (firstLoad) {
                binding.llPortfolioChartSection.visibility = View.VISIBLE
                binding.flPortfolioChart.visibility = View.VISIBLE
                binding.tvChartEmpty.visibility = View.GONE
            }
            setChartSkeleton(firstLoad)
            binding.portfolioChartProgress.visibility = if (loading && chartEverShown) View.VISIBLE else View.GONE
        }

        viewModel.portfolioChartData.observe(viewLifecycleOwner) { points ->
            allChartPoints = points
            if (points.size >= 2) {
                chartEverShown = true
                binding.llPortfolioChartSection.visibility = View.VISIBLE
                binding.flPortfolioChart.visibility = View.VISIBLE
                binding.tvChartEmpty.visibility = View.GONE
                drawPortfolioChart(points)
            } else if (chartEverShown) {
                // This range has no history: say so, but keep the chips so it can be changed.
                binding.flPortfolioChart.visibility = View.GONE
                binding.tvChartEmpty.visibility = View.VISIBLE
            } else {
                binding.llPortfolioChartSection.visibility = View.GONE
            }
        }

        viewModel.portfolioRefreshed.observe(viewLifecycleOwner) { freshSummary ->
            if (freshSummary != null) showUpdateBanner() else dismissUpdateBanner()
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { loading ->
            if (!loading) {
                binding.swipeRefreshHome.isRefreshing = false
            }
        }
    }

    // ── Portfolio chart ─────────────────────────────────────────────────

    // ── Update banner ─────────────────────────────────────────────────

    // The root's animateLayoutChanges fades the banner in and eases the page down (and back up).
    private fun showUpdateBanner() {
        val banner = binding.bannerPortfolioUpdated
        banner.setOnClickListener { viewModel.applyRefreshedPortfolio() }
        banner.clipToOutline = true   // keep the tap ripple inside the rounded corners
        banner.visibility = View.VISIBLE
    }

    private fun dismissUpdateBanner() {
        binding.bannerPortfolioUpdated.visibility = View.GONE
    }

    private fun setupPortfolioChart() {
        binding.portfolioLineChart.apply {
            description.isEnabled = false
            legend.isEnabled      = false
            setBackgroundColor(Color.TRANSPARENT)
            // Touch drives scrubbing only: dragging along the line highlights a day and the
            // header shows that day's value (see showScrubbedPoint). Pan/zoom stay off.
            setTouchEnabled(true)
            // Must be true even though we never pan: BarLineChartTouchListener.onTouch returns
            // early when drag AND scale are both off, so highlight-per-drag would never fire. With
            // scaling off the chart is always fully zoomed out, so a drag highlights, not pans.
            isDragEnabled             = true
            setScaleEnabled(false)
            setPinchZoom(false)
            isDoubleTapToZoomEnabled  = false
            isHighlightPerTapEnabled  = true
            isHighlightPerDragEnabled = true
            setDrawGridBackground(false)
            setDrawBorders(false)
            minOffset = 0f
            setExtraOffsets(12f, 8f, 12f, 12f)
            setNoDataText("")

            // Right axis — thin horizontal grid lines + compact value labels
            axisRight.apply {
                isEnabled        = true
                setDrawAxisLine(false)
                setDrawLabels(true)
                setLabelCount(4, false)
                // @color/text_tertiary / @color/divider_color flip with the theme (unlike a
                // fixed hex), so the grid stays a faint, legible hairline in both — a literal
                // zinc-100 grid line was invisible on the light background it was tuned for and
                // glaring against the near-black dark background.
                textColor  = requireContext().getColor(R.color.text_tertiary)
                textSize   = 10f
                typeface   = androidx.core.content.res.ResourcesCompat.getFont(requireContext(), R.font.geist_mono)
                gridColor  = requireContext().getColor(R.color.divider_color)
                gridLineWidth = 0.5f
                // Outside the plot, so the line ends before the labels instead of running through them.
                setPosition(com.github.mikephil.charting.components.YAxis.YAxisLabelPosition.OUTSIDE_CHART)
                xOffset = 8f
                // Indian units (₹6L, ₹1.2Cr) — not the Western "₹600K"
                valueFormatter = object : com.github.mikephil.charting.formatter.ValueFormatter() {
                    override fun getAxisLabel(value: Float, axis: com.github.mikephil.charting.components.AxisBase?): String =
                        FormatUtils.formatCompactInr(value.toDouble())
                }
            }

            axisLeft.isEnabled = false

            // Bottom axis — show first and last date labels only
            xAxis.apply {
                isEnabled        = true
                setDrawAxisLine(false)
                setDrawGridLines(false)
                setDrawLabels(true)
                setLabelCount(2, true)
                setAvoidFirstLastClipping(true)  // Prevents months from cutting off at edges
                position  = com.github.mikephil.charting.components.XAxis.XAxisPosition.BOTTOM
                textColor = requireContext().getColor(R.color.text_tertiary)
                textSize  = 10f
                typeface  = androidx.core.content.res.ResourcesCompat.getFont(requireContext(), R.font.geist_mono)
                yOffset   = 4f
            }

            setOnChartValueSelectedListener(object : com.github.mikephil.charting.listener.OnChartValueSelectedListener {
                override fun onValueSelected(e: Entry?, h: com.github.mikephil.charting.highlight.Highlight?) {
                    e?.let { showScrubbedPoint(it.x.toInt()) }
                }
                override fun onNothingSelected() = restoreScrubHeader()
            })

            // Keep the parent (scroll / pull-to-refresh) from stealing a scrub, and clear the
            // highlight the moment the finger lifts. Returns false so the chart still handles it.
            (this as View).setOnTouchListener(View.OnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> v.parent?.requestDisallowInterceptTouchEvent(true)
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        if (ev.actionMasked == android.view.MotionEvent.ACTION_UP) v.performClick()
                        v.parent?.requestDisallowInterceptTouchEvent(false)
                        v.post { highlightValues(null); restoreScrubHeader() }
                    }
                }
                false
            })
        }
    }

    // ── Chart skeleton ───────────────────────────────────────────────────

    private var skeletonAnimator: android.animation.ObjectAnimator? = null

    private fun setChartSkeleton(show: Boolean) {
        if (_binding == null) return
        val skeleton = binding.vChartSkeleton
        if (show) {
            skeleton.visibility = View.VISIBLE
            if (skeletonAnimator == null) {
                skeletonAnimator = android.animation.ObjectAnimator.ofFloat(skeleton, View.ALPHA, 0.35f, 0.85f).apply {
                    duration = 900L
                    repeatMode = android.animation.ValueAnimator.REVERSE
                    repeatCount = android.animation.ValueAnimator.INFINITE
                    start()
                }
            }
        } else {
            skeletonAnimator?.cancel()
            skeletonAnimator = null
            skeleton.alpha = 1f
            skeleton.visibility = View.GONE
        }
    }

    // ── Range chips ──────────────────────────────────────────────────────

    // Built on demand, never cached: the binding's views are recreated with the fragment's view.
    private fun rangeChips() = mapOf(
        ChartRange.WEEK    to binding.tvRange1w,
        ChartRange.MONTH   to binding.tvRange1m,
        ChartRange.QUARTER to binding.tvRange3m,
        ChartRange.YEAR    to binding.tvRange1y
    )

    private fun setupRangeChips() {
        rangeChips().forEach { (range, chip) ->
            chip.setOnClickListener {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                viewModel.setChartRange(range)
            }
        }
    }

    /** Selected = an ink block with inverse text: selection is ink, never the neon. */
    private fun styleRangeChips(selected: ChartRange) {
        if (_binding == null) return
        rangeChips().forEach { (range, chip) ->
            val on = range == selected
            chip.background = if (on) ContextCompat.getDrawable(requireContext(), R.drawable.bg_selected_chip) else null
            chip.setTextColor(requireContext().getColor(if (on) R.color.on_ink else R.color.text_secondary))
            chip.isSelected = on
        }
    }

    // ── Chart scrubbing ──────────────────────────────────────────────────

    private val scrubDateFmt = SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault())

    /** Header shows the highlighted day's value + date while a finger is down on the chart. */
    private fun showScrubbedPoint(index: Int) {
        if (_binding == null) return
        val point = allChartPoints.getOrNull(index) ?: return
        if (index != scrubbedIndex) {
            binding.portfolioLineChart.performHapticFeedback(
                // The finer scrub tick is API 34+; older devices get the standard clock tick.
                if (android.os.Build.VERSION.SDK_INT >= 34) android.view.HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
                else android.view.HapticFeedbackConstants.CLOCK_TICK
            )
            scrubbedIndex = index
        }
        // A count-up may still be running on this view; stop it so it doesn't overwrite the scrub.
        (binding.tvPortfolioCurrent.tag as? android.animation.ValueAnimator)?.cancel()
        binding.tvPortfolioCurrent.text = FormatUtils.formatPrice(point.current, "INR")
        binding.tvDate.text = scrubDateFmt.format(java.util.Date(point.timestamp))
    }

    private fun restoreScrubHeader() {
        if (_binding == null || scrubbedIndex == -1) return
        scrubbedIndex = -1
        currentPortfolioTotal?.let { binding.tvPortfolioCurrent.text = FormatUtils.formatPrice(it, "INR") }
        setupDateTime()
    }

    private fun drawPortfolioChart(points: List<PortfolioChartPoint>) {
        if (_binding == null || points.size < 2) return

        // An ink line over a dotted ink fill, whichever way the range went: the coloured P&L above
        // the chart says up or down. Both colours flip with the theme (values-night).
        val lineColor = requireContext().getColor(R.color.text_primary)
        val density   = resources.displayMetrics.density
        val dotFill   = app.trackone.ui.util.DotGridDrawable(
            color     = requireContext().getColor(R.color.chart_dots),
            spacingPx = 5f * density,
            radiusPx  = 0.9f * density
        )

        val currentEntries = points.mapIndexed { i, p -> Entry(i.toFloat(), p.current.toFloat()) }

        val currentDataSet = LineDataSet(currentEntries, "Current").apply {
            color = lineColor
            setDrawCircles(false)
            setDrawCircleHole(false)
            setDrawValues(false)
            lineWidth      = 2f
            mode           = LineDataSet.Mode.CUBIC_BEZIER
            cubicIntensity = 0.15f
            setDrawFilled(true)
            fillDrawable = dotFill
            // The visible labels belong to the RIGHT axis. Bound to the (hidden) left axis, the
            // line autoscaled to its own min/max while the labels stayed pinned to a different
            // scale — harmless while the data spanned zero to the total, wrong for a real history.
            axisDependency = com.github.mikephil.charting.components.YAxis.AxisDependency.RIGHT
            isHighlightEnabled = true
            highLightColor = requireContext().getColor(R.color.text_secondary)
            highlightLineWidth = 1f
            enableDashedHighlightLine(6f, 4f, 0f)
            setDrawHorizontalHighlightIndicator(false)
        }

        // Date formatter for xAxis labels
        val span = points.last().timestamp - points.first().timestamp
        val dateFmt = if (span < 200L * 24 * 60 * 60 * 1000) {
            SimpleDateFormat("d MMM", Locale.getDefault())
        } else {
            SimpleDateFormat("MMM ''yy", Locale.getDefault())   // Sep '25 — "Sep 25" reads as a day
        }
        binding.portfolioLineChart.xAxis.valueFormatter = object : com.github.mikephil.charting.formatter.ValueFormatter() {
            override fun getAxisLabel(value: Float, axis: com.github.mikephil.charting.components.AxisBase?): String {
                val idx = value.toInt().coerceIn(0, points.size - 1)
                return dateFmt.format(java.util.Date(points[idx].timestamp))
            }
        }

        binding.portfolioLineChart.apply {
            // Axis hugs the data: a portfolio moving a couple of percent would otherwise be a flat
            // line at the top of a zero-based axis.
            // Ends on round gridlines, so the dotted fill stops at the bottom label, not below it.
            val ticks = ChartAxis.ticks(points.minOf { it.current }, points.maxOf { it.current })
            axisRight.axisMinimum = ticks.lo.toFloat()
            axisRight.axisMaximum = ticks.hi.toFloat()
            axisRight.granularity = ticks.step.toFloat()
            axisRight.setLabelCount(ticks.count, true)
            data = LineData(currentDataSet)
            animateX(700)
            invalidate()
        }
    }

    // ── Index cards ──────────────────────────────────────────────────────

    private enum class Index { NIFTY, SENSEX, SP500, NASDAQ }

    private fun applyIndex(res: Resource<IndexData>, index: Index) {
        if (_binding == null) return
        when (res) {
            is Resource.Success -> {
                val data   = res.data

                when (index) {
                    Index.NIFTY -> {
                        if (!niftyAnimated) {
                            binding.tvNiftyPrice.animateNumberFromZero(data.price) {
                                FormatUtils.formatIndexPrice(it, data.currency)
                            }
                            niftyAnimated = true
                        } else {
                            binding.tvNiftyPrice.text = FormatUtils.formatIndexPrice(data.price, data.currency)
                        }
                        styleIndexChange(data.changePercent, binding.tvNiftyChange)
                    }
                    Index.SENSEX -> {
                        if (!sensexAnimated) {
                            binding.tvSensexPrice.animateNumberFromZero(data.price) {
                                FormatUtils.formatIndexPrice(it, data.currency)
                            }
                            sensexAnimated = true
                        } else {
                            binding.tvSensexPrice.text = FormatUtils.formatIndexPrice(data.price, data.currency)
                        }
                        styleIndexChange(data.changePercent, binding.tvSensexChange)
                    }
                    Index.SP500 -> {
                        if (!sp500Animated) {
                            binding.tvSp500Price.animateNumberFromZero(data.price) {
                                FormatUtils.formatIndexPrice(it, data.currency)
                            }
                            sp500Animated = true
                        } else {
                            binding.tvSp500Price.text = FormatUtils.formatIndexPrice(data.price, data.currency)
                        }
                        styleIndexChange(data.changePercent, binding.tvSp500Change)
                    }
                    Index.NASDAQ -> {
                        if (!nasdaqAnimated) {
                            binding.tvNasdaqPrice.animateNumberFromZero(data.price) {
                                FormatUtils.formatIndexPrice(it, data.currency)
                            }
                            nasdaqAnimated = true
                        } else {
                            binding.tvNasdaqPrice.text = FormatUtils.formatIndexPrice(data.price, data.currency)
                        }
                        styleIndexChange(data.changePercent, binding.tvNasdaqChange)
                    }
                }
            }
            is Resource.Error -> { /* keep placeholder text */ }
            else -> {}
        }
    }

    /** An index's move as coloured text led by its arrow; money direction never gets a filled pill. */
    private fun styleIndexChange(changePercent: Double, tvChange: TextView) {
        tvChange.text = FormatUtils.formatMovePercent(changePercent)
        tvChange.setTextColor(requireContext().getColor(MoneyColor.forChange(changePercent)))
    }

    // ── Top Movers ───────────────────────────────────────────────────────

    private fun applyTopMovers(movers: List<TopMover>) {
        if (_binding == null) return
        binding.topMoverLoading.visibility = View.GONE

        if (movers.isEmpty()) {
            binding.llTopMoversContainer.visibility = View.GONE
            binding.topMoverEmpty.visibility        = View.VISIBLE
            return
        }

        binding.topMoverEmpty.visibility        = View.GONE
        binding.llTopMoversContainer.visibility = View.VISIBLE
        binding.llTopMoversContainer.removeAllViews()

        movers.forEach { mover ->
            binding.llTopMoversContainer.addView(buildMoverRow(mover))
        }
    }

    private fun buildMoverRow(mover: TopMover): View {
        val stock  = mover.stock
        val chipW  = (116 * resources.displayMetrics.density).toInt()

        // Compact vertical card chip
        val card = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(chipW, LinearLayout.LayoutParams.WRAP_CONTENT).also {
                it.marginEnd = 8.dp
            }
            background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_index_card)
            setPadding(12.dp, 12.dp, 12.dp, 12.dp)
            isClickable = true
            isFocusable = true
            foreground  = ContextCompat.getDrawable(
                requireContext(), android.R.attr.selectableItemBackground.let { attr ->
                    val ta = requireContext().obtainStyledAttributes(intArrayOf(attr))
                    val res = ta.getResourceId(0, 0); ta.recycle(); res
                }
            )
            // Cards are rebuilt on every update, so the transition name follows the symbol (see WatchlistAdapter).
            if (stock.symbol == sharedMoverSymbol) transitionName = StockDetailActivity.SHARED_ELEMENT
            setOnClickListener {
                sharedMoverSymbol = stock.symbol
                StockDetailActivity.start(requireActivity(), stock.symbol, it)
            }
        }

        // Symbol
        val tvSymbol = TextView(requireContext()).apply {
            text = mover.label
            textSize = 14f
            setTextColor(requireContext().getColor(R.color.text_primary))
            typeface = androidx.core.content.res.ResourcesCompat.getFont(requireContext(), R.font.inter_bold)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        // Current price
        val tvPrice = TextView(requireContext()).apply {
            // Metals: the futures quote is USD per troy ounce, but a holding is in grams — show ₹/g.
            text = mover.unitPriceInr?.let { FormatUtils.formatPrice(it, "INR") + "/g" }
                ?: FormatUtils.formatPrice(stock.currentPrice, stock.currency)
            textSize = 12f
            typeface = androidx.core.content.res.ResourcesCompat.getFont(requireContext(), R.font.geist_mono)
            setTextColor(requireContext().getColor(R.color.text_secondary))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = 4.dp }
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        // % change: coloured text led by its arrow, no pill behind it
        val tvChange = TextView(requireContext()).apply {
            text = FormatUtils.formatMovePercent(stock.changePercent)
            textSize = 12f
            typeface = androidx.core.content.res.ResourcesCompat.getFont(requireContext(), R.font.inter_bold)
            setTextColor(requireContext().getColor(MoneyColor.forChange(stock.changePercent)))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = 4.dp }
        }

        // Optional holding-value sub-line (INR), shown whenever the value is known
        if (mover.currentVal > 0.0) {
            val tvInv = TextView(requireContext()).apply {
                text = FormatUtils.formatPrice(mover.currentVal, "INR")
                textSize = 12f
                setTextColor(requireContext().getColor(R.color.text_primary))
                typeface = androidx.core.content.res.ResourcesCompat.getFont(requireContext(), R.font.geist_mono_bold)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                // No background for this sub-line as per user request
            }
            
            val invContainer = LinearLayout(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = 4.dp }
                addView(tvInv)
            }
            
            card.addView(tvSymbol)
            card.addView(tvPrice)
            card.addView(tvChange)
            card.addView(invContainer)
        } else {
            card.addView(tvSymbol)
            card.addView(tvPrice)
            card.addView(tvChange)
        }

        return card
    }

    // ── Portfolio Summary ────────────────────────────────────────────────

    private fun applyPortfolioSummary(summary: PortfolioSummary?) {
        if (_binding == null) return
        if (summary == null || summary.totalCurrent == 0.0) {
            binding.cardPortfolioSummary.visibility = View.GONE
            return
        }

        binding.cardPortfolioSummary.visibility = View.VISIBLE
        currentPortfolioTotal = summary.totalCurrent

        // Animate total on first load
        if (!portfolioAnimated) {
            binding.tvPortfolioCurrent.animateNumberFromZero(summary.totalCurrent) {
                FormatUtils.formatPrice(it, "INR")
            }
            portfolioAnimated = true
        } else {
            binding.tvPortfolioCurrent.text = FormatUtils.formatPrice(summary.totalCurrent, "INR")
        }

        // P&L chip
        val hasPnL = kotlin.math.abs(summary.absChange) > 0.01
        if (hasPnL) {
            val isGain   = summary.absChange >= 0
            val arrow    = if (isGain) "↗" else "↘"
            val absStr   = FormatUtils.formatPrice(kotlin.math.abs(summary.absChange), "INR")
            val pctStr   = FormatUtils.formatChangePercent(summary.pctChange)
            binding.tvPortfolioPnl.text = "$arrow $absStr ($pctStr)"

            // The move is coloured text; money direction never gets a filled pill.
            binding.tvPortfolioPnl.setTextColor(requireContext().getColor(MoneyColor.forChange(summary.absChange)))
            binding.tvPortfolioPnl.setTypeface(
                androidx.core.content.res.ResourcesCompat.getFont(requireContext(), R.font.inter_bold),
                android.graphics.Typeface.NORMAL
            )
            binding.tvPortfolioPnl.background = null
            binding.tvPortfolioPnl.visibility = View.VISIBLE
        } else {
            binding.tvPortfolioPnl.visibility = View.GONE
        }

        // Invested → Now row (only when at least one buy price is known)
        val hasInvestedData = summary.totalInvested > 0.01 && hasPnL
        if (hasInvestedData) {
            binding.llInvestedRow.visibility = View.VISIBLE
            binding.tvInvestedAmount.text    = FormatUtils.formatPrice(summary.totalInvested, "INR")
            binding.tvCurrentAmountInline.text = FormatUtils.formatPrice(summary.totalCurrent, "INR")
        } else {
            binding.llInvestedRow.visibility = View.GONE
        }
    }


    // ── Market Hours Dialog ──────────────────────────────────────────────

    private enum class Market { US, INDIA }

    private fun showMarketHoursDialog(market: Market) {
        val deviceTz = TimeZone.getDefault()
        val sdf = SimpleDateFormat("h:mm a", Locale.getDefault()).apply { timeZone = deviceTz }

        fun toDeviceTime(hour: Int, minute: Int, sourceTz: TimeZone): String {
            val cal = Calendar.getInstance(sourceTz).apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
            }
            return sdf.format(cal.time)
        }

        val dialogView = layoutInflater.inflate(R.layout.dialog_market_hours, null)
        val tvTitle    = dialogView.findViewById<TextView>(R.id.tv_market_title)
        val tvStatus   = dialogView.findViewById<TextView>(R.id.tv_market_status)
        val tvRegOpen  = dialogView.findViewById<TextView>(R.id.tv_regular_open)
        val tvRegClose = dialogView.findViewById<TextView>(R.id.tv_regular_close)
        val tvExt1     = dialogView.findViewById<TextView>(R.id.tv_extended_1)
        val tvExt2     = dialogView.findViewById<TextView>(R.id.tv_extended_2)
        val tvTz       = dialogView.findViewById<TextView>(R.id.tv_timezone_info)

        when (market) {
            Market.US -> {
                val estTz  = TimeZone.getTimeZone("America/New_York")
                val isOpen = MarketUtils.isUsMarketOpen()
                tvTitle.text  = "NYSE / NASDAQ"
                tvStatus.text = if (isOpen) "OPEN" else "CLOSED"
                // Open = ink pill with inverse text, closed = neutral pill. Both flip with the theme.
                tvStatus.setTextColor(
                    requireContext().getColor(if (isOpen) R.color.on_ink else R.color.text_primary)
                )
                tvStatus.backgroundTintList = android.content.res.ColorStateList.valueOf(
                    requireContext().getColor(if (isOpen) R.color.ink else R.color.surface_variant)
                )
                tvRegOpen.text  = toDeviceTime(9,  30, estTz)
                tvRegClose.text = toDeviceTime(16,  0, estTz)
                tvExt1.text = "Pre: from ${toDeviceTime(4,  0, estTz)}"
                tvExt2.text = "After: until ${toDeviceTime(20, 0, estTz)}"
            }
            Market.INDIA -> {
                val istTz  = TimeZone.getTimeZone("Asia/Kolkata")
                val isOpen = MarketUtils.isIndiaMarketOpen()
                tvTitle.text  = "NSE / BSE"
                tvStatus.text = if (isOpen) "OPEN" else "CLOSED"
                // Open = ink pill with inverse text, closed = neutral pill. Both flip with the theme.
                tvStatus.setTextColor(
                    requireContext().getColor(if (isOpen) R.color.on_ink else R.color.text_primary)
                )
                tvStatus.backgroundTintList = android.content.res.ColorStateList.valueOf(
                    requireContext().getColor(if (isOpen) R.color.ink else R.color.surface_variant)
                )
                tvRegOpen.text  = toDeviceTime(9,  15, istTz)
                tvRegClose.text = toDeviceTime(15, 30, istTz)
                tvExt1.text = "Pre-open: from ${toDeviceTime(9, 0, istTz)}"
                tvExt2.visibility = View.GONE
            }
        }

        tvTz.text = "Times shown in your local timezone (${deviceTz.getDisplayName(false, TimeZone.SHORT)})"

        AlertDialog.Builder(requireContext(), R.style.ThemeOverlay_App_MaterialAlertDialog)
            .setView(dialogView)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // An infinite animator would otherwise keep the destroyed view tree alive.
        skeletonAnimator?.cancel()
        skeletonAnimator = null
        _binding = null
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /** Convert Int to px (density-independent). */
    private val Int.dp: Int get() =
        (this * resources.displayMetrics.density).toInt()
}
