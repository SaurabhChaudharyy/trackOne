package com.saurabh.financewidget.data.repository

import android.content.Context
import android.net.Uri
import com.saurabh.financewidget.data.database.AssetType
import com.saurabh.financewidget.data.database.NetWorthAssetEntity
import com.saurabh.financewidget.data.database.NetWorthDao
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.dhatim.fastexcel.reader.ReadableWorkbook
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

// ─── Result types ──────────────────────────────────────────────────────────────

sealed class CsvImportResult {
    data class Success(val imported: Int, val skipped: Int) : CsvImportResult()
    data class Failure(val reason: String, val cause: Throwable? = null) : CsvImportResult()
}

// ─── Broker formats ────────────────────────────────────────────────────────────

private enum class BrokerFormat {
    /** HDFC Securities / Angel / similar
     *  Columns: Stock Name | ISIN | Quantity | Average buy price | Buy value |
     *           Closing price | Closing value | Unrealised P&L
     */
    FORMAT_A,

    /** Zerodha / Groww / similar
     *  Columns: Instrument | Qty. | Avg. cost | LTP | Invested | Cur. val | P&L | Net chg.
     */
    FORMAT_B
}

// ─── A single parsed row (common intermediate) ─────────────────────────────────

private data class RawRow(val cols: List<String>)

// ─── Repository ────────────────────────────────────────────────────────────────

@Singleton
class BrokerCsvRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val netWorthDao: NetWorthDao
) {

    companion object {
        val IMPORT_MIME_TYPES = arrayOf(
            "text/csv",
            "text/plain",
            "text/comma-separated-values",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", // xlsx
            "application/vnd.ms-excel",   // xls (treated same — we only read xlsx)
            "*/*"
        )
    }

    /**
     * Parses a broker portfolio CSV/TSV/XLSX and **appends** the parsed holdings
     * to the existing net-worth assets as STOCK_IN entries. Existing data is NOT deleted.
     */
    suspend fun importFromUri(uri: Uri): CsvImportResult = withContext(Dispatchers.IO) {
        try {
            val mimeType = context.contentResolver.getType(uri) ?: ""
            val fileName = uri.lastPathSegment?.lowercase() ?: ""
            val isXlsx = mimeType.contains("spreadsheetml") ||
                         mimeType.contains("ms-excel") ||
                         fileName.endsWith(".xlsx") ||
                         fileName.endsWith(".xls")

            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return@withContext CsvImportResult.Failure("Could not open the selected file.")

            val (header, rows) = if (isXlsx) {
                parseXlsx(inputStream)
            } else {
                parseCsv(inputStream)
            } ?: return@withContext CsvImportResult.Failure(
                "The file has no data rows. Make sure you exported the portfolio holdings from your broker."
            )

            val format = detectFormat(header)
                ?: return@withContext CsvImportResult.Failure(
                    "Unrecognised broker format.\n\n" +
                    "Supported brokers:\n" +
                    "• HDFC Securities / Angel (columns: Stock Name, ISIN, Quantity, Average buy price…)\n" +
                    "• Zerodha / Groww (columns: Instrument, Qty., Avg. cost, LTP…)\n\n" +
                    "Make sure you're selecting the Holdings / Portfolio export file."
                )

            val now = System.currentTimeMillis()
            var imported = 0
            var skipped = 0
            val entities = mutableListOf<NetWorthAssetEntity>()

            for (row in rows) {
                val entity = when (format) {
                    BrokerFormat.FORMAT_A -> parseFormatA(row.cols, now)
                    BrokerFormat.FORMAT_B -> parseFormatB(row.cols, now)
                }
                if (entity != null) {
                    entities.add(entity)
                    imported++
                } else {
                    skipped++
                }
            }

            if (entities.isEmpty()) {
                return@withContext CsvImportResult.Failure(
                    "No valid holdings found in the file. The file may be empty or the columns could not be parsed."
                )
            }

            netWorthDao.insertAssets(entities)
            CsvImportResult.Success(imported = imported, skipped = skipped)

        } catch (e: Exception) {
            CsvImportResult.Failure("Import failed: ${e.message}", e)
        }
    }

    // ── Parsers ────────────────────────────────────────────────────────────────

    /**
     * Reads an XLSX file using fastexcel. Picks the first non-empty sheet and
     * returns (header row, data rows).
     */
    private fun parseXlsx(stream: InputStream): Pair<List<String>, List<RawRow>>? {
        ReadableWorkbook(stream).use { wb ->
            // wb.sheets is a Java Stream<Sheet> — collect to a list first
            val sheets = wb.sheets.collect(java.util.stream.Collectors.toList())
            val sheet = sheets.firstOrNull { !it.name.isNullOrBlank() }
                ?: wb.firstSheet

            // openStream() also returns a Java Stream<Row>
            val allRows = mutableListOf<List<String>>()
            sheet.openStream().use { rowStream ->
                rowStream.forEach { row ->
                    allRows.add(
                        (0 until row.cellCount).map { i ->
                            row.getCell(i)?.rawValue?.trim() ?: ""
                        }
                    )
                }
            }

            // Find the first row that looks like a header (has at least 4 non-empty cells)
            val headerIdx = allRows.indexOfFirst { cols ->
                cols.count { it.isNotBlank() } >= 4
            }
            if (headerIdx < 0 || headerIdx >= allRows.size - 1) return null

            val header = allRows[headerIdx].map { it.lowercase() }
            val rows = allRows.drop(headerIdx + 1)
                .filter { cols -> cols.any { it.isNotBlank() } }
                .map { RawRow(it) }
            return Pair(header, rows)
        }
    }

    /**
     * Reads a CSV/TSV text file. Returns (header row, data rows).
     */
    private fun parseCsv(stream: InputStream): Pair<List<String>, List<RawRow>>? {
        val raw = stream.bufferedReader().use { it.readText() }
        if (raw.isBlank()) return null

        val lines = raw.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (lines.size < 2) return null

        val delimiter = detectCsvDelimiter(lines.first())
        val header = lines.first().split(delimiter).map { it.trim().lowercase() }
        val rows = lines.drop(1).map { line ->
            RawRow(line.split(delimiter).map { it.trim() })
        }
        return Pair(header, rows)
    }

    // ── Row parsers ────────────────────────────────────────────────────────────

    // Format A: Stock Name | ISIN | Qty | Avg Buy Price | Buy Value | Closing Price | Closing Value | P&L
    private fun parseFormatA(cols: List<String>, now: Long): NetWorthAssetEntity? {
        if (cols.size < 7) return null
        val name     = cols[0].ifBlank { return null }
        val quantity = cols[2].toDoubleOrNull() ?: return null
        val buyPrice = cols[3].toDoubleOrNull() ?: return null
        val curValue = cols[6].toDoubleOrNull() ?: return null
        return buildEntity(name.trim(), quantity, buyPrice, curValue, now)
    }

    // Format B: Instrument | Qty. | Avg. cost | LTP | Invested | Cur. val | P&L | Net chg.
    private fun parseFormatB(cols: List<String>, now: Long): NetWorthAssetEntity? {
        if (cols.size < 6) return null
        val symbol   = cols[0].ifBlank { return null }
        val quantity = cols[1].toDoubleOrNull() ?: return null
        val buyPrice = cols[2].toDoubleOrNull() ?: return null
        val curValue = cols[5].toDoubleOrNull() ?: return null
        return buildEntity(symbol.trim().uppercase(), quantity, buyPrice, curValue, now)
    }

    private fun buildEntity(
        name: String, quantity: Double, buyPrice: Double, curValue: Double, now: Long
    ) = NetWorthAssetEntity(
        id           = 0,
        name         = name,
        assetType    = AssetType.STOCK_IN,
        quantity     = quantity,
        buyPrice     = buyPrice,
        currentValue = curValue,
        currency     = "INR",
        notes        = "",
        addedAt      = now,
        updatedAt    = now
    )

    // ── format / delimiter detection ───────────────────────────────────────────

    private fun detectCsvDelimiter(headerLine: String): String = when {
        headerLine.contains('\t') -> "\t"
        headerLine.contains(',')  -> ","
        else                      -> "\\s{2,}".toRegex().find(headerLine)?.value ?: " "
    }

    private fun detectFormat(headers: List<String>): BrokerFormat? {
        val joined = headers.joinToString("|")
        return when {
            joined.contains("stock name") || joined.contains("isin")          -> BrokerFormat.FORMAT_A
            joined.contains("instrument") || joined.contains("avg. cost") ||
                joined.contains("cur. val") || joined.contains("qty.")        -> BrokerFormat.FORMAT_B
            else -> null
        }
    }
}
