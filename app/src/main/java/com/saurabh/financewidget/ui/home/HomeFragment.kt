package com.saurabh.financewidget.ui.home

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
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
import com.saurabh.financewidget.R
import com.saurabh.financewidget.data.database.StockEntity
import com.saurabh.financewidget.databinding.FragmentHomeBinding
import com.saurabh.financewidget.ui.detail.StockDetailActivity
import com.saurabh.financewidget.utils.AnimationUtils.animateNumberFromZero
import com.saurabh.financewidget.utils.FormatUtils
import com.saurabh.financewidget.utils.MarketUtils
import com.saurabh.financewidget.utils.Resource
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

        binding.tvUsMarketStatus.text = if (usOpen) "Open" else "Closed"
        binding.tvUsMarketStatus.setTextColor(
            requireContext().getColor(if (usOpen) R.color.neon_highlight else R.color.text_tertiary)
        )

        binding.tvIndiaMarketStatus.text = if (indiaOpen) "Open" else "Closed"
        binding.tvIndiaMarketStatus.setTextColor(
            requireContext().getColor(if (indiaOpen) R.color.neon_highlight else R.color.text_tertiary)
        )
    }

    // ── Index card click → StockDetailActivity ──────────────────────────

    private fun setupIndexCardClicks() {
        binding.cardNifty.setOnClickListener  { StockDetailActivity.start(requireContext(), "^NSEI") }
        binding.cardSensex.setOnClickListener { StockDetailActivity.start(requireContext(), "^BSESN") }
        binding.cardSp500.setOnClickListener  { StockDetailActivity.start(requireContext(), "^GSPC") }
        binding.cardNasdaq.setOnClickListener { StockDetailActivity.start(requireContext(), "^IXIC") }
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
        binding.swipeRefreshHome.setColorSchemeColors(
            requireContext().getColor(R.color.neon_highlight)
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

        viewModel.portfolioChartData.observe(viewLifecycleOwner) { points ->
            allChartPoints = points
            if (points.size >= 2) {
                binding.llPortfolioChartSection.visibility = View.VISIBLE
                drawPortfolioChart(points)
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

    private fun showUpdateBanner() {
        val banner = binding.bannerPortfolioUpdated
        if (banner.visibility == View.VISIBLE) return
        banner.visibility = View.VISIBLE
        banner.translationY = banner.height.toFloat().coerceAtLeast(120f)
        banner.animate()
            .translationY(0f)
            .setDuration(300)
            .setInterpolator(DecelerateInterpolator())
            .start()
        banner.setOnClickListener {
            viewModel.applyRefreshedPortfolio()
        }
    }

    private fun dismissUpdateBanner() {
        val banner = binding.bannerPortfolioUpdated
        if (banner.visibility != View.VISIBLE) return
        banner.animate()
            .translationY(banner.height.toFloat().coerceAtLeast(120f))
            .setDuration(250)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction { banner.visibility = View.GONE }
            .start()
    }

    private fun setupPortfolioChart() {
        binding.portfolioLineChart.apply {
            description.isEnabled = false
            legend.isEnabled      = false
            setBackgroundColor(Color.TRANSPARENT)
            setTouchEnabled(false)
            isDragEnabled             = false
            setScaleEnabled(false)
            setPinchZoom(false)
            isDoubleTapToZoomEnabled  = false
            isHighlightPerTapEnabled  = false
            isHighlightPerDragEnabled = false
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
                axisMinimum      = 0f  // Prevents negative Y-axis values
                textColor  = Color.parseColor("#A1A1AA")   // zinc-400
                textSize   = 9f
                gridColor  = Color.parseColor("#F4F4F5")   // zinc-100
                gridLineWidth = 0.5f
                setPosition(com.github.mikephil.charting.components.YAxis.YAxisLabelPosition.INSIDE_CHART)
                // Format values as ₹XL or ₹XK
                valueFormatter = object : com.github.mikephil.charting.formatter.ValueFormatter() {
                    override fun getAxisLabel(value: Float, axis: com.github.mikephil.charting.components.AxisBase?): String {
                        return when {
                            value >= 10_00_000 -> "₹${String.format("%.1f", value / 100_000)}L"
                            value >= 1_000     -> "₹${String.format("%.0f", value / 1_000)}K"
                            value <= 0         -> "₹0"
                            else               -> "₹${value.toInt()}"
                        }
                    }
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
                textColor = Color.parseColor("#A1A1AA")
                textSize  = 9f
                yOffset   = 4f
            }
        }
    }

    private fun drawPortfolioChart(points: List<PortfolioChartPoint>) {
        if (_binding == null || points.size < 2) return

        val isGain   = points.last().current >= points.first().current
        val lineColor = if (isGain) Color.parseColor("#16A34A") else Color.parseColor("#DC2626")
        val fillStartColor = if (isGain) Color.parseColor("#2216A34A") else Color.parseColor("#22DC2626")
        val fillEndColor   = Color.parseColor("#0016A34A")  // transparent bottom

        val currentEntries = points.mapIndexed { i, p -> Entry(i.toFloat(), p.current.toFloat()) }

        // Gradient fill drawable
        val gradientDrawable = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(fillStartColor, fillEndColor)
        )

        val currentDataSet = LineDataSet(currentEntries, "Current").apply {
            color = lineColor
            setDrawCircles(false)
            setDrawCircleHole(false)
            setDrawValues(false)
            lineWidth      = 1.5f
            mode           = LineDataSet.Mode.CUBIC_BEZIER
            cubicIntensity = 0.15f
            setDrawFilled(true)
            fillDrawable = gradientDrawable
            isHighlightEnabled = false
        }

        // Date formatter for xAxis labels
        val span = points.last().timestamp - points.first().timestamp
        val dateFmt = if (span < 30L * 24 * 60 * 60 * 1000) {
            SimpleDateFormat("d MMM", Locale.getDefault())
        } else {
            SimpleDateFormat("MMM yy", Locale.getDefault())
        }
        binding.portfolioLineChart.xAxis.valueFormatter = object : com.github.mikephil.charting.formatter.ValueFormatter() {
            override fun getAxisLabel(value: Float, axis: com.github.mikephil.charting.components.AxisBase?): String {
                val idx = value.toInt().coerceIn(0, points.size - 1)
                return dateFmt.format(java.util.Date(points[idx].timestamp))
            }
        }

        binding.portfolioLineChart.apply {
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
                val isGain = data.changePercent >= 0
                val pct    = FormatUtils.formatChangePercent(data.changePercent)

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
                        binding.tvNiftyChange.text = pct
                        styleIndexPill(isGain, binding.pillNifty, binding.tvNiftyChange, binding.ivNiftyTrend)
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
                        binding.tvSensexChange.text = pct
                        styleIndexPill(isGain, binding.pillSensex, binding.tvSensexChange, binding.ivSensexTrend)
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
                        binding.tvSp500Change.text = pct
                        styleIndexPill(isGain, binding.pillSp500, binding.tvSp500Change, binding.ivSp500Trend)
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
                        binding.tvNasdaqChange.text = pct
                        styleIndexPill(isGain, binding.pillNasdaq, binding.tvNasdaqChange, binding.ivNasdaqTrend)
                    }
                }
            }
            is Resource.Error -> { /* keep placeholder text */ }
            else -> {}
        }
    }

    private fun styleIndexPill(
        isGain: Boolean,
        pill: LinearLayout,
        tvChange: TextView,
        ivTrend: ImageView
    ) {
        pill.background = requireContext().getDrawable(
            if (isGain) R.drawable.bg_gain_pill else R.drawable.bg_loss_pill
        )
        tvChange.setTextColor(
            requireContext().getColor(if (isGain) R.color.gain_green else R.color.loss_red)
        )
        ivTrend.setImageResource(
            if (isGain) R.drawable.ic_trending_up else R.drawable.ic_trending_down
        )
        ivTrend.imageTintList = android.content.res.ColorStateList.valueOf(
            requireContext().getColor(if (isGain) R.color.gain_green else R.color.loss_red)
        )
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
        val isGain = stock.changePercent >= 0
        val chipW  = (110 * resources.displayMetrics.density).toInt()

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
            setOnClickListener { StockDetailActivity.start(requireContext(), stock.symbol) }
        }

        // Symbol
        val tvSymbol = TextView(requireContext()).apply {
            text = stock.symbol.removePrefix("^")
            textSize = 12f
            setTextColor(requireContext().getColor(R.color.text_primary))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        // Current price
        val tvPrice = TextView(requireContext()).apply {
            text = FormatUtils.formatPrice(stock.currentPrice, stock.currency)
            textSize = 11f
            setTextColor(requireContext().getColor(R.color.text_secondary))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = 4.dp }
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        // % change pill
        val pill = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            background = ContextCompat.getDrawable(
                requireContext(), if (isGain) R.drawable.bg_gain_pill else R.drawable.bg_loss_pill
            )
            setPadding(5.dp, 2.dp, 5.dp, 2.dp)
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = 6.dp }
        }
        val ivTrend = ImageView(requireContext()).apply {
            setImageResource(if (isGain) R.drawable.ic_trending_up else R.drawable.ic_trending_down)
            imageTintList = android.content.res.ColorStateList.valueOf(
                requireContext().getColor(if (isGain) R.color.gain_green else R.color.loss_red)
            )
            layoutParams = LinearLayout.LayoutParams(9.dp, 9.dp).also { it.marginEnd = 2.dp }
        }
        val tvChange = TextView(requireContext()).apply {
            text = FormatUtils.formatChangePercent(stock.changePercent)
            textSize = 10f
            setTextColor(requireContext().getColor(if (isGain) R.color.gain_green else R.color.loss_red))
        }
        pill.addView(ivTrend)
        pill.addView(tvChange)

        // Optional invested → current sub-line
        if (mover.invested > 0.0) {
            val pnl   = mover.currentVal - mover.invested
            val color = requireContext().getColor(if (pnl >= 0) R.color.gain_green else R.color.loss_red)
            val tvInv = TextView(requireContext()).apply {
                text = FormatUtils.formatPrice(mover.currentVal, stock.currency)
                textSize = 10f
                setTextColor(color)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = 4.dp }
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            card.addView(tvSymbol)
            card.addView(tvPrice)
            card.addView(pill)
            card.addView(tvInv)
        } else {
            card.addView(tvSymbol)
            card.addView(tvPrice)
            card.addView(pill)
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

            val textColor = requireContext().getColor(if (isGain) R.color.gain_green else R.color.loss_red)
            val bgColor   = requireContext().getColor(if (isGain) R.color.gain_green_bg else R.color.loss_red_bg)

            binding.tvPortfolioPnl.setTextColor(textColor)
            (binding.tvPortfolioPnl.background.mutate() as? android.graphics.drawable.GradientDrawable)
                ?.setColor(bgColor)
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
                tvStatus.setTextColor(requireContext().getColor(if (isOpen) R.color.background else R.color.text_primary))
                tvStatus.backgroundTintList = android.content.res.ColorStateList.valueOf(
                    requireContext().getColor(if (isOpen) R.color.neon_highlight else R.color.surface_variant)
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
                tvStatus.setTextColor(requireContext().getColor(if (isOpen) R.color.background else R.color.text_primary))
                tvStatus.backgroundTintList = android.content.res.ColorStateList.valueOf(
                    requireContext().getColor(if (isOpen) R.color.neon_highlight else R.color.surface_variant)
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
        _binding = null
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /** Convert Int to px (density-independent). */
    private val Int.dp: Int get() =
        (this * resources.displayMetrics.density).toInt()
}
