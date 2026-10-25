package app.trackone.data.repository

import android.content.Context
import android.net.Uri
import app.trackone.data.database.ExpenseCategory
import app.trackone.data.database.ExpenseDao
import app.trackone.data.database.ExpenseTransactionEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

// ─── Universal expense row — the bank/card-agnostic canonical shape ───────────
//
// Mirrors UniversalHolding in BrokerCsvRepository.kt: every bank/card statement
// format normalizes into this one shape before touching persistence.

data class UniversalExpenseRow(
    val date: Long,
    val amount: Double,
    val description: String,
    val category: ExpenseCategory,
    val account: String,
    val currency: String
) {
    fun toEntity(): ExpenseTransactionEntity = ExpenseTransactionEntity(
        date = date,
        amount = amount,
        description = description,
        category = category,
        account = account,
        currency = currency
    )
}

// ─── Repository — file access and persistence ─────────────────────────────────
//
// Same split as BrokerCsvRepository: parsing lives in ExpenseCsvParser below and
// has no Context/DAO dependency; this class owns Uri access and the Room insert.

@Singleton
class ExpenseCsvRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val expenseDao: ExpenseDao
) {

    companion object {
        val IMPORT_MIME_TYPES = BrokerCsvRepository.IMPORT_MIME_TYPES
    }

    suspend fun importFromUri(
        uri: Uri,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): CsvImportResult = withContext(Dispatchers.IO) {
        try {
            val mimeType = context.contentResolver.getType(uri) ?: ""
            val fileName = uri.lastPathSegment?.lowercase() ?: ""
            val isXlsx = mimeType.contains("spreadsheetml") ||
                mimeType.contains("ms-excel") ||
                fileName.endsWith(".xlsx") ||
                fileName.endsWith(".xls")

            val fileBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@withContext CsvImportResult.Failure("Could not open the selected file.")

            when (val outcome = ExpenseCsvParser.parse(fileBytes, isXlsx)) {
                is ExpenseCsvParser.ParseOutcome.Failure -> CsvImportResult.Failure(outcome.reason)
                is ExpenseCsvParser.ParseOutcome.Success -> {
                    persist(outcome.rows, onProgress)
                    CsvImportResult.Success(imported = outcome.rows.size, skipped = outcome.skipped)
                }
            }
        } catch (e: Exception) {
            CsvImportResult.Failure("Import failed: ${e.message}", e)
        }
    }

    private suspend fun persist(rows: List<UniversalExpenseRow>, onProgress: (Int, Int) -> Unit) {
        for ((index, row) in rows.withIndex()) {
            expenseDao.insertExpense(row.toEntity())
            onProgress(index + 1, rows.size)
        }
    }
}

// ─── Parser — pure: no Context, no DAO ─────────────────────────────────────────
//
// NOTE: no bank/card formats are implemented yet. Every real bank/card statement
// export has its own column layout, and guessing at that layout for financial
// data risks silently wrong amounts/dates — so this intentionally ships with
// zero recognised formats until real sample exports (headers/structure, values
// can be redacted) are available for the specific banks/cards actually used.
// detectFormat() always returning null means every import surfaces the
// "unrecognised format" message below rather than a wrong parse.
//
// To add a format once samples arrive: add a case to BankFormat, a header
// signature in detectFormat(), and a row-parser function — same shape as
// BrokerCsvParser's FORMAT_A/FORMAT_B/etc.
internal object ExpenseCsvParser {

    sealed class ParseOutcome {
        data class Success(val rows: List<UniversalExpenseRow>, val skipped: Int) : ParseOutcome()
        data class Failure(val reason: String) : ParseOutcome()
    }

    internal enum class BankFormat
    // (intentionally empty — see file header comment)

    internal fun detectFormat(headers: List<String>): BankFormat? = null

    internal fun parse(fileBytes: ByteArray, isXlsx: Boolean): ParseOutcome {
        return ParseOutcome.Failure(
            "Bank/card statement import isn't set up for any bank yet.\n\n" +
            "This needs a real sample export (with amounts redacted, just the column layout) " +
            "from your bank/card to wire up correctly."
        )
    }
}
