package com.saurabh.financewidget.data.database

import androidx.room.TypeConverter

class Converters {
    @TypeConverter
    fun fromAssetType(type: AssetType): String = type.name

    @TypeConverter
    fun toAssetType(value: String): AssetType = AssetType.valueOf(value)
}
