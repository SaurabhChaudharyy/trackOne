package app.trackone.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Closed set, matching AssetType's existing style. OTHER absorbs anything a future
 * ExpenseCategorizer can't match from a statement's description — real bank/card statement
 * descriptions are messier than the app's 8 asset types, so this bucket is expected to see
 * real use, not just be a rare fallback.
 */
enum class ExpenseCategory {
    GROCERIES,
    RENT,
    UTILITIES,
    DINING,
    TRANSPORT,
    SHOPPING,
    ENTERTAINMENT,
    HEALTH,
    INCOME,
    TRANSFER,
    OTHER
}

/**
 * One line item from a bank/card statement import. Separate domain from NetWorthAssetEntity —
 * this tracks spending, not holdings.
 */
@Entity(tableName = "expense_transactions")
data class ExpenseTransactionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val date: Long,
    val amount: Double,
    val description: String,
    val category: ExpenseCategory = ExpenseCategory.OTHER,
    /** Which account/card this line came from, e.g. "HDFC Savings ...1234". */
    val account: String,
    val currency: String = "INR",
    val createdAt: Long = System.currentTimeMillis()
)
