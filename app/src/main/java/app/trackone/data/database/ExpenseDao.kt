package app.trackone.data.database

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ExpenseDao {

    @Query("SELECT * FROM expense_transactions ORDER BY date DESC")
    fun getAllExpenses(): LiveData<List<ExpenseTransactionEntity>>

    @Query("SELECT * FROM expense_transactions ORDER BY date DESC")
    suspend fun getAllExpensesSync(): List<ExpenseTransactionEntity>

    @Query(
        "SELECT SUM(amount) FROM expense_transactions " +
        "WHERE date >= :startOfMonthMillis AND date < :startOfNextMonthMillis"
    )
    fun getMonthlyTotal(startOfMonthMillis: Long, startOfNextMonthMillis: Long): LiveData<Double?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExpense(expense: ExpenseTransactionEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExpenses(expenses: List<ExpenseTransactionEntity>)

    @Delete
    suspend fun deleteExpense(expense: ExpenseTransactionEntity)

    @Query("DELETE FROM expense_transactions")
    suspend fun deleteAllExpenses()
}
