package com.saurabh.financewidget.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class AssetType {
    STOCK_IN,   
    STOCK_US,   
    MF,         
    GOLD,       
    SILVER,     
    CRYPTO,     
    CASH,       
    BANK        
}

@Entity(tableName = "networth_assets")
data class NetWorthAssetEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,           
    val assetType: AssetType,
    val quantity: Double = 1.0, 
    val buyPrice: Double = 0.0, 
    val currentValue: Double,   
    val currency: String = "INR",
    val notes: String = "",
    val addedAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
