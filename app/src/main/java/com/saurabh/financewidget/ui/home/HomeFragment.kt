package com.saurabh.financewidget.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.saurabh.financewidget.R
import com.saurabh.financewidget.data.database.StockEntity
import com.saurabh.financewidget.databinding.FragmentHomeBinding
import com.saurabh.financewidget.utils.FormatUtils
import com.saurabh.financewidget.utils.MarketUtils
import com.saurabh.financewidget.utils.Resource
import dagger.hilt.android.AndroidEntryPoint
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.Timer
import java.util.TimerTask

@AndroidEntryPoint
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val viewModel: HomeViewModel by viewModels()

    private var clockTimer: Timer? = null

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
        setupMarketStatusChips()
        observeViewModel()
    }

    override fun onResume() {
        super.onResume()
        startClock()
        updateMarketStatus()
    }

    override fun onPause() {
        super.onPause()
        stopClock()
    }

    // ── Date / Greeting / Clock ──────────────────────────────────────────

    private fun setupDateTime() {
        val now = Calendar.getInstance()

        // Date: "WEDNESDAY, 18 MARCH 2026"
        val dateFmt = SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault())
        binding.tvDate.text = dateFmt.format(now.time).uppercase()

        // Greeting based on hour
        val hour = now.get(Calendar.HOUR_OF_DAY)
        binding.tvGreeting.text = when {
            hour < 12 -> "Good morning."
            hour < 17 -> "Good afternoon."
            else      -> "Good evening."
        }
    }

    private fun startClock() {
        clockTimer?.cancel()
        clockTimer = Timer()
        clockTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                val activity = activity ?: return
                activity.runOnUiThread { updateTime() }
            }
        }, 0L, 60_000L) // refresh every minute
    }

    private fun stopClock() {
        clockTimer?.cancel()
        clockTimer = null
    }

    private fun updateTime() {
        if (_binding == null) return
        val cal = Calendar.getInstance()
        val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())
        val tzAbbr = TimeZone.getDefault().getDisplayName(false, TimeZone.SHORT)
        binding.tvTime.text = "${timeFmt.format(cal.time)} $tzAbbr"
    }

    // ── Market Status ────────────────────────────────────────────────────

    private fun setupMarketStatusChips() {
        binding.chipUsMarket.setOnClickListener { showMarketHoursDialog(Market.US) }
        binding.chipIndiaMarket.setOnClickListener { showMarketHoursDialog(Market.INDIA) }
    }

    private fun updateMarketStatus() {
        if (_binding == null) return
        val usOpen     = MarketUtils.isUsMarketOpen()
        val indiaOpen  = MarketUtils.isIndiaMarketOpen()

        binding.tvUsMarketStatus.text = if (usOpen) "Open" else "Closed"
        binding.tvUsMarketStatus.setTextColor(
            requireContext().getColor(if (usOpen) R.color.neon_highlight else R.color.text_tertiary)
        )

        binding.tvIndiaMarketStatus.text = if (indiaOpen) "Open" else "Closed"
        binding.tvIndiaMarketStatus.setTextColor(
            requireContext().getColor(if (indiaOpen) R.color.neon_highlight else R.color.text_tertiary)
        )
    }

    // ── Observe ViewModel ────────────────────────────────────────────────

    private fun observeViewModel() {
        viewModel.nifty.observe(viewLifecycleOwner)  { res -> applyIndex(res, Index.NIFTY) }
        viewModel.sensex.observe(viewLifecycleOwner) { res -> applyIndex(res, Index.SENSEX) }
        viewModel.sp500.observe(viewLifecycleOwner)  { res -> applyIndex(res, Index.SP500) }
        viewModel.nasdaq.observe(viewLifecycleOwner) { res -> applyIndex(res, Index.NASDAQ) }

        viewModel.topMover.observe(viewLifecycleOwner) { stock -> applyTopMover(stock) }

        viewModel.isLoading.observe(viewLifecycleOwner) { loading ->
            if (loading) {
                binding.topMoverLoading.visibility = View.VISIBLE
                binding.cardTopMover.visibility    = View.GONE
                binding.topMoverEmpty.visibility   = View.GONE
            }
        }
    }

    private enum class Index { NIFTY, SENSEX, SP500, NASDAQ }

    private fun applyIndex(res: Resource<IndexData>, index: Index) {
        if (_binding == null) return
        when (res) {
            is Resource.Success -> {
                val data = res.data
                val isGain = data.changePercent >= 0
                val pct = FormatUtils.formatChangePercent(data.changePercent)

                when (index) {
                    Index.NIFTY -> {
                        binding.tvNiftyPrice.text  = FormatUtils.formatIndexPrice(data.price, data.currency)
                        binding.tvNiftyChange.text = pct
                        styleIndexPill(isGain, binding.pillNifty, binding.tvNiftyChange, binding.ivNiftyTrend)
                    }
                    Index.SENSEX -> {
                        binding.tvSensexPrice.text  = FormatUtils.formatIndexPrice(data.price, data.currency)
                        binding.tvSensexChange.text = pct
                        styleIndexPill(isGain, binding.pillSensex, binding.tvSensexChange, binding.ivSensexTrend)
                    }
                    Index.SP500 -> {
                        binding.tvSp500Price.text  = FormatUtils.formatIndexPrice(data.price, data.currency)
                        binding.tvSp500Change.text = pct
                        styleIndexPill(isGain, binding.pillSp500, binding.tvSp500Change, binding.ivSp500Trend)
                    }
                    Index.NASDAQ -> {
                        binding.tvNasdaqPrice.text  = FormatUtils.formatIndexPrice(data.price, data.currency)
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
        pill: android.widget.LinearLayout,
        tvChange: android.widget.TextView,
        ivTrend: android.widget.ImageView
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

    private fun applyTopMover(stock: StockEntity?) {
        if (_binding == null) return
        binding.topMoverLoading.visibility = View.GONE
        if (stock == null) {
            binding.cardTopMover.visibility   = View.GONE
            binding.topMoverEmpty.visibility  = View.VISIBLE
            return
        }
        binding.topMoverEmpty.visibility  = View.GONE
        binding.cardTopMover.visibility   = View.VISIBLE

        val isGain = stock.changePercent >= 0
        binding.tvTopMoverSymbol.text = stock.symbol.removePrefix("^")
        binding.tvTopMoverName.text   = stock.companyName.ifBlank { stock.symbol }
        binding.tvTopMoverPrice.text  = if (stock.symbol.startsWith("^"))
            FormatUtils.formatIndexPrice(stock.currentPrice, stock.currency)
        else
            FormatUtils.formatPrice(stock.currentPrice, stock.currency)
        binding.tvTopMoverChange.text = FormatUtils.formatChangePercent(stock.changePercent)

        styleIndexPill(isGain, binding.pillTopMover, binding.tvTopMoverChange, binding.ivTopMoverTrend)
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
        val tvTitle  = dialogView.findViewById<android.widget.TextView>(R.id.tv_market_title)
        val tvStatus = dialogView.findViewById<android.widget.TextView>(R.id.tv_market_status)
        val tvRegOpen  = dialogView.findViewById<android.widget.TextView>(R.id.tv_regular_open)
        val tvRegClose = dialogView.findViewById<android.widget.TextView>(R.id.tv_regular_close)
        val tvExt1   = dialogView.findViewById<android.widget.TextView>(R.id.tv_extended_1)
        val tvExt2   = dialogView.findViewById<android.widget.TextView>(R.id.tv_extended_2)
        val tvTz     = dialogView.findViewById<android.widget.TextView>(R.id.tv_timezone_info)

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
}
