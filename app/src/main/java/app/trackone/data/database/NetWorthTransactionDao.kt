package app.trackone.data.database

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface NetWorthTransactionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(transaction: NetWorthTransactionEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(transactions: List<NetWorthTransactionEntity>)

    /** Full buy/sell history for one holding, most recent first. */
    @Query("SELECT * FROM networth_transactions WHERE assetId = :assetId ORDER BY transactionDate DESC")
    fun getTransactionsForAsset(assetId: Long): LiveData<List<NetWorthTransactionEntity>>

    @Query("SELECT * FROM networth_transactions WHERE assetId = :assetId ORDER BY transactionDate DESC")
    suspend fun getTransactionsForAssetSync(assetId: Long): List<NetWorthTransactionEntity>

    @Query("SELECT * FROM networth_transactions ORDER BY transactionDate DESC")
    fun getAllTransactions(): LiveData<List<NetWorthTransactionEntity>>

    @Query("SELECT * FROM networth_transactions ORDER BY transactionDate DESC")
    suspend fun getAllTransactionsSync(): List<NetWorthTransactionEntity>

    @Query("DELETE FROM networth_transactions WHERE assetId = :assetId")
    suspend fun deleteTransactionsForAsset(assetId: Long)

    @Query("DELETE FROM networth_transactions")
    suspend fun deleteAllTransactions()
}
