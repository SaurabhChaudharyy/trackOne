package app.trackone.data.database

import androidx.room.TypeConverter

class Converters {
    @TypeConverter
    fun fromAssetType(type: AssetType): String = type.name

    @TypeConverter
    fun toAssetType(value: String): AssetType = AssetType.valueOf(value)

    @TypeConverter
    fun fromTransactionType(type: TransactionType): String = type.name

    @TypeConverter
    fun toTransactionType(value: String): TransactionType = TransactionType.valueOf(value)

    @TypeConverter
    fun fromExpenseCategory(category: ExpenseCategory): String = category.name

    @TypeConverter
    fun toExpenseCategory(value: String): ExpenseCategory = ExpenseCategory.valueOf(value)
}
