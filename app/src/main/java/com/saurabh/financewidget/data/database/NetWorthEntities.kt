package com.saurabh.financewidget.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class AssetType {
    STOCK_IN,   // Indian stock (NSE/BSE)
    STOCK_US,   // US stock
    MF,         // Mutual Fund
    GOLD,       // Gold (digital/physical)
    CRYPTO,     // Cryptocurrency
    CASH,       // Cash on hand
    BANK        // Bank balance
}

@Entity(tableName = "networth_assets")
data class NetWorthAssetEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,           // e.g. "RELIANCE", "Bitcoin", "SBI Savings"
    val assetType: AssetType,
    val quantity: Double = 1.0, // units / grams / lots
    val buyPrice: Double = 0.0, // avg buy price (optional, for stocks)
    val currentValue: Double,   // total current value in INR
    val currency: String = "INR",
    val notes: String = "",
    val addedAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
