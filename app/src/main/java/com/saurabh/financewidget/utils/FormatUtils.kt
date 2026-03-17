package com.saurabh.financewidget.utils

import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

object FormatUtils {

    fun formatPrice(price: Double, currency: String = "USD"): String {
        return if (currency == "INR") {
            val format = NumberFormat.getCurrencyInstance(Locale("en", "IN"))
            format.maximumFractionDigits = 2
            format.minimumFractionDigits = 2
            format.format(price)
        } else {
            val format = NumberFormat.getCurrencyInstance(Locale.US)
            format.maximumFractionDigits = if (price < 1.0) 4 else 2
            format.minimumFractionDigits = 2
            format.format(price)
        }
    }

    fun formatIndexPrice(price: Double, currency: String = "USD"): String {
        val format = if (currency == "INR") {
            NumberFormat.getNumberInstance(Locale("en", "IN"))
        } else {
            NumberFormat.getNumberInstance(Locale.US)
        }
        format.maximumFractionDigits = 2
        format.minimumFractionDigits = 2
        return format.format(price)
    }

    fun formatChange(change: Double): String {
        val prefix = if (change >= 0) "+" else ""
        return "$prefix${String.format("%.2f", change)}"
    }

    fun formatChangePercent(changePercent: Double): String {
        val prefix = if (changePercent >= 0) "+" else ""
        return "$prefix${String.format("%.2f", changePercent)}%"
    }

    fun formatVolume(volume: Long): String {
        return when {
            volume >= 1_000_000_000 -> "${String.format("%.2f", volume / 1_000_000_000.0)}B"
            volume >= 1_000_000 -> "${String.format("%.2f", volume / 1_000_000.0)}M"
            volume >= 1_000 -> "${String.format("%.1f", volume / 1_000.0)}K"
            else -> volume.toString()
        }
    }

    fun formatMarketCap(marketCap: Double): String {
        return when {
            marketCap >= 1_000_000_000_000.0 -> "${String.format("%.2f", marketCap / 1_000_000_000_000.0)}T"
            marketCap >= 1_000_000_000.0     -> "${String.format("%.2f", marketCap / 1_000_000_000.0)}B"
            marketCap >= 1_000_000.0         -> "${String.format("%.2f", marketCap / 1_000_000.0)}M"
            else                             -> "${String.format("%.0f", marketCap)}"
        }
    }

    fun formatTimestamp(timestamp: Long): String {
        val sdf = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
        return sdf.format(Date(timestamp * 1000))
    }

    fun formatLastUpdated(timestamp: Long): String {
        val diff = System.currentTimeMillis() - timestamp
        return when {
            diff < 60_000 -> "Just now"
            diff < 3_600_000 -> "${diff / 60_000}m ago"
            diff < 86_400_000 -> "${diff / 3_600_000}h ago"
            else -> SimpleDateFormat("MMM dd", Locale.getDefault()).format(Date(timestamp))
        }
    }
}

object MarketUtils {

    fun isUsMarketOpen(): Boolean {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("America/New_York"))
        val day = cal.get(Calendar.DAY_OF_WEEK)
        val time = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        return day != Calendar.SATURDAY && day != Calendar.SUNDAY && time in (9 * 60 + 30)..(16 * 60)
    }

    fun isIndiaMarketOpen(): Boolean {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"))
        val day = cal.get(Calendar.DAY_OF_WEEK)
        val time = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        return day != Calendar.SATURDAY && day != Calendar.SUNDAY && time in (9 * 60 + 15)..(15 * 60 + 30)
    }

    fun isMarketOpen(): Boolean = isUsMarketOpen() || isIndiaMarketOpen()

    fun getUpdateIntervalMinutes(): Long = if (isMarketOpen()) 15L else 60L
}
