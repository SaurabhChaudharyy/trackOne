package com.saurabh.financewidget.data.repository

import android.content.Context
import android.net.Uri
import com.saurabh.financewidget.data.database.AssetType
import com.saurabh.financewidget.data.database.NetWorthAssetEntity
import com.saurabh.financewidget.data.database.NetWorthDao
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

// ─── Result types ──────────────────────────────────────────────────────────────

sealed class CsvImportResult {
    data class Success(val imported: Int, val skipped: Int) : CsvImportResult()
    data class Failure(val reason: String, val cause: Throwable? = null) : CsvImportResult()
}

// ─── Broker formats ────────────────────────────────────────────────────────────

private enum class BrokerFormat {
    /**
     * HDFC Securities / Angel / similar
     * Columns: Stock Name | ISIN | Quantity | Average buy price | Buy value |
     *          Closing price | Closing value | Unrealised P&L
     */
    FORMAT_A,

    /**
     * Zerodha / Groww / similar
     * Columns: Instrument | Qty. | Avg. cost | LTP | Invested | Cur. val | P&L | Net chg.
     */
    FORMAT_B,

    /**
     * Vested / Interactive Brokers / similar (US stocks)
     * Columns: name | quantity | buyPrice | currentValue
     * buyPrice may be blank — treated as 0.0 (break-even).
     */
    FORMAT_C
}

// ─── A single parsed row (common intermediate) ────────────────────────────────

private data class RawRow(val cols: List<String>)

// ─── Repository ───────────────────────────────────────────────────────────────

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
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-excel",
            "*/*"
        )

        private const val PREFS_NAME = "broker_import_hashes"
        private const val KEY_HASHES  = "imported_file_hashes"

        /**
         * Maps Vested's full company-name strings (as exported in the `name` column)
         * to the Yahoo Finance ticker symbol used by NetWorthRepository for live price lookups.
         * Interactive Brokers already exports tickers, so they pass through unchanged.
         */
        val VESTED_NAME_TO_TICKER = mapOf(
            "APPLE INC"                          to "AAPL",
            "ADVANCED MICRO DEVICES INC"         to "AMD",
            "AMAZON COM INC"                     to "AMZN",
            "ASML HLDG NV"                       to "ASML",
            "BANK AMERICA CORP"                  to "BAC",
            "SALESFORCE INC"                     to "CRM",
            "DISNEY WALT CO"                     to "DIS",
            "ALPHABET INC CAP STK CL C"          to "GOOG",
            "ALPHABET INC CAP STK CL A"          to "GOOGL",
            "INTUIT"                             to "INTU",
            "JPMORGAN CHASE & CO"                to "JPM",
            "MASTERCARD INCORPORATED CL A"       to "MA",
            "META PLATFORMS INC CL A"            to "META",
            "MICROSOFT CORP"                     to "MSFT",
            "NETFLIX INC"                        to "NFLX",
            "NVIDIA CORPORATION"                 to "NVDA",
            "QUALCOMM INC"                       to "QCOM",
            "INVESCO QQQ TR UNIT SER 1"          to "QQQ",
            "SHOPIFY INC CL A SUB VTG SHS"       to "SHOP",
            "SPOTIFY TECHNOLOGY S A SHS"         to "SPOT",
            "TESLA INC"                          to "TSLA",
            "TAIWAN SEMICONDUCTOR MANUFACT"      to "TSM",
            "VISA INC COM CL A"                  to "V",
            "VANGUARD INDEX FDS S&P 500 ETF"     to "VOO",
            "VANGUARD INDEX FDS SP 500 ETF"      to "VOO",
            "BROADCOM INC"                       to "AVGO",
            "COSTCO WHOLESALE CORP"              to "COST",
            "BERKSHIRE HATHAWAY INC CL B"        to "BRK-B",
            "BERKSHIRE HATHAWAY INC CL A"        to "BRK-A",
            "EATON VANCE FLOATING RATE TR"       to "EOSE",
            "DOCUSIGN INC"                       to "DOCU",
            "PALANTIR TECHNOLOGIES INC"          to "PLTR",
            "PANW"                               to "PANW",
            "PALO ALTO NETWORKS INC"             to "PANW"
        )
    }

    /**
     * Parses a broker portfolio CSV/TSV/XLSX and upserts the parsed holdings
     * into the net-worth assets table. Existing assets with the same name+type
     * are updated in-place (quantity, buyPrice, currentValue) — never duplicated.
     *
     * A SHA-256 hash of the raw file bytes is stored in SharedPreferences after
     * a successful import. Re-uploading the exact same file is rejected instantly.
     */
    suspend fun importFromUri(uri: Uri): CsvImportResult = withContext(Dispatchers.IO) {
        try {
            val mimeType = context.contentResolver.getType(uri) ?: ""
            val fileName = uri.lastPathSegment?.lowercase() ?: ""
            val isXlsx = mimeType.contains("spreadsheetml") ||
                mimeType.contains("ms-excel") ||
                fileName.endsWith(".xlsx") ||
                fileName.endsWith(".xls")

            // Read all bytes upfront — needed for both hashing and parsing.
            val fileBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@withContext CsvImportResult.Failure("Could not open the selected file.")

            // ── Hash check ────────────────────────────────────────────────────
            val fileHash = computeSha256(fileBytes)
            if (isAlreadyImported(fileHash)) {
                return@withContext CsvImportResult.Failure(
                    "This file has already been imported.\n\n" +
                    "If your portfolio has changed, please export a fresh file from your broker."
                )
            }

            val parsed = (if (isXlsx) parseXlsx(fileBytes) else parseCsv(fileBytes.inputStream()))
                ?: return@withContext CsvImportResult.Failure(
                    "The file has no data rows. Make sure you exported the portfolio holdings from your broker."
                )

            val (header, rows) = parsed
            val format = detectFormat(header)
                ?: return@withContext CsvImportResult.Failure(
                    "Unrecognised broker format.\n\n" +
                        "Supported brokers:\n" +
                        "  HDFC Securities / Angel (columns: Stock Name, ISIN, Quantity, Average buy price...)\n" +
                        "  Zerodha / Groww (columns: Instrument, Qty., Avg. cost, LTP...)\n" +
                        "  Vested / Interactive Brokers (columns: name, quantity, buyPrice, currentValue)\n\n" +
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
                    BrokerFormat.FORMAT_C -> parseFormatC(row.cols, now)
                }
                if (entity != null) {
                    entities.add(entity); imported++
                } else {
                    skipped++
                }
            }

            if (entities.isEmpty()) {
                return@withContext CsvImportResult.Failure(
                    "No valid holdings found in the file. The file may be empty or the columns could not be parsed."
                )
            }

            // ── Upsert loop ───────────────────────────────────────────────────
            // For each parsed row: update the existing holding if name+type matches,
            // otherwise insert as a new holding. This prevents duplicate rows on
            // re-import of an updated broker export.
            for (entity in entities) {
                val existing = netWorthDao.findAssetByNameAndType(entity.name, entity.assetType)
                if (existing != null) {
                    netWorthDao.updateAsset(
                        existing.copy(
                            quantity     = entity.quantity,
                            buyPrice     = entity.buyPrice,
                            currentValue = entity.currentValue,
                            updatedAt    = entity.updatedAt
                        )
                    )
                } else {
                    netWorthDao.insertAsset(entity)
                }
            }

            // Record hash only after a fully successful import.
            recordImportHash(fileHash)
            CsvImportResult.Success(imported = imported, skipped = skipped)

        } catch (e: Exception) {
            CsvImportResult.Failure("Import failed: ${e.message}", e)
        }
    }

    // ── File hash helpers ─────────────────────────────────────────────────────

    /** Computes the SHA-256 hex digest of the given bytes. */
    private fun computeSha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun isAlreadyImported(hash: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_HASHES, emptySet())?.contains(hash) == true
    }

    private fun recordImportHash(hash: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getStringSet(KEY_HASHES, emptySet())?.toMutableSet() ?: mutableSetOf()
        existing.add(hash)
        prefs.edit().putStringSet(KEY_HASHES, existing).apply()
    }

    // ── XLSX parser ───────────────────────────────────────────────────────────
    //
    // XLSX is a ZIP archive of XML files. No third-party library needed — we
    // use Android's built-in ZipInputStream and XmlPullParser (android.util.Xml).
    //
    //  xl/sharedStrings.xml  ->  string table; cells of type "s" point here by index
    //  xl/worksheets/sheet1.xml  ->  the actual grid rows/cells

    /** Accepts already-buffered bytes so the caller can hash the file before parsing. */
    private fun parseXlsx(bytes: ByteArray): Pair<List<String>, List<RawRow>>? {

        val sharedStrings = readSharedStrings(bytes)
        val rows = readSheetRows(bytes, sharedStrings)

        if (rows.size < 2) return null

        // The first row with >= 4 non-blank cells is treated as the header.
        val headerIdx = rows.indexOfFirst { cols -> cols.count { it.isNotBlank() } >= 4 }
        if (headerIdx < 0 || headerIdx >= rows.size - 1) return null

        val header = rows[headerIdx].map { it.lowercase() }
        val dataRows = rows.drop(headerIdx + 1)
            .filter { cols -> cols.any { it.isNotBlank() } }
            .map { RawRow(it) }

        return Pair(header, dataRows)
    }

    /** Parses xl/sharedStrings.xml; each &lt;si&gt; element = one string entry. */
    private fun readSharedStrings(xlsxBytes: ByteArray): List<String> {
        val result = mutableListOf<String>()
        ZipInputStream(xlsxBytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "xl/sharedStrings.xml") {
                    val parser = android.util.Xml.newPullParser()
                    parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                    parser.setInput(zip, "UTF-8")

                    var text = StringBuilder()
                    var inSi = false
                    var event = parser.eventType

                    while (event != XmlPullParser.END_DOCUMENT) {
                        when (event) {
                            XmlPullParser.START_TAG -> when (parser.name) {
                                "si" -> { inSi = true; text = StringBuilder() }
                                "t" -> if (inSi) text.append(parser.nextText())
                            }
                            XmlPullParser.END_TAG -> if (parser.name == "si") {
                                result.add(text.toString())
                                inSi = false
                            }
                        }
                        event = parser.next()
                    }
                    break
                }
                entry = zip.nextEntry
            }
        }
        return result
    }

    /** Parses xl/worksheets/sheet*.xml into a list-of-rows (each row = list of cell strings). */
    private fun readSheetRows(
        xlsxBytes: ByteArray,
        sharedStrings: List<String>
    ): List<List<String>> {
        val result = mutableListOf<List<String>>()
        ZipInputStream(xlsxBytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name.matches(Regex("xl/worksheets/sheet\\d+\\.xml"))) {
                    val parser = android.util.Xml.newPullParser()
                    parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                    parser.setInput(zip, "UTF-8")

                    // (columnIndex, cellValue) pairs for the current row
                    var currentRow = mutableListOf<Pair<Int, String>>()
                    var cellType  = ""
                    var cellRef   = ""
                    var cellValue = ""
                    var inCell    = false

                    var event = parser.eventType
                    while (event != XmlPullParser.END_DOCUMENT) {
                        when (event) {
                            XmlPullParser.START_TAG -> when (parser.name) {
                                "row" -> currentRow = mutableListOf()
                                "c" -> {
                                    cellRef   = parser.getAttributeValue(null, "r") ?: ""
                                    cellType  = parser.getAttributeValue(null, "t") ?: ""
                                    cellValue = ""
                                    inCell    = true
                                }
                                "v", "t" -> if (inCell) cellValue = parser.nextText()
                            }
                            XmlPullParser.END_TAG -> when (parser.name) {
                                "c" -> {
                                    val colIdx = colRefToIndex(cellRef)
                                    val resolved = when (cellType) {
                                        "s" -> cellValue.toIntOrNull()
                                            ?.let { sharedStrings.getOrNull(it) } ?: cellValue
                                        "b" -> if (cellValue == "1") "TRUE" else "FALSE"
                                        else -> cellValue
                                    }
                                    currentRow.add(Pair(colIdx, resolved.trim()))
                                    inCell = false
                                }
                                "row" -> {
                                    if (currentRow.isNotEmpty()) {
                                        val maxCol = currentRow.maxOf { it.first }
                                        val row = Array(maxCol + 1) { "" }
                                        currentRow.forEach { (col, v) -> row[col] = v }
                                        result.add(row.toList())
                                    }
                                }
                            }
                        }
                        event = parser.next()
                    }
                    break
                }
                entry = zip.nextEntry
            }
        }
        return result
    }

    /**
     * Converts an Excel column reference (e.g. "A", "B", "AA", or "B3") to a 0-based index.
     */
    private fun colRefToIndex(ref: String): Int {
        val col = ref.takeWhile { it.isLetter() }.uppercase()
        return col.fold(0) { acc, c -> acc * 26 + (c - 'A' + 1) } - 1
    }

    // ── CSV parser ────────────────────────────────────────────────────────────

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

    // ── Row parsers ───────────────────────────────────────────────────────────

    // Format A: Stock Name | ISIN | Qty | Avg Buy Price | Buy Value | Closing Price | Closing Value | P&L
    private fun parseFormatA(cols: List<String>, now: Long): NetWorthAssetEntity? {
        if (cols.size < 7) return null
        val name = cols[0].ifBlank { return null }
        val quantity = cols[2].toDoubleOrNull() ?: return null
        val buyPrice = cols[3].toDoubleOrNull() ?: return null
        val curValue = cols[6].toDoubleOrNull() ?: return null
        return buildEntity(name.trim(), quantity, buyPrice, curValue, "INR", AssetType.STOCK_IN, now)
    }

    // Format B: Instrument | Qty. | Avg. cost | LTP | Invested | Cur. val | P&L | Net chg.
    private fun parseFormatB(cols: List<String>, now: Long): NetWorthAssetEntity? {
        if (cols.size < 6) return null
        val symbol = cols[0].ifBlank { return null }
        val quantity = cols[1].toDoubleOrNull() ?: return null
        val buyPrice = cols[2].toDoubleOrNull() ?: return null
        val curValue = cols[5].toDoubleOrNull() ?: return null
        return buildEntity(symbol.trim().uppercase(), quantity, buyPrice, curValue, "INR", AssetType.STOCK_IN, now)
    }

    // Format C: name | quantity | buyPrice | currentValue  (Vested / Interactive Brokers — US stocks)
    //
    // The `name` column may be either a ticker ("AMD") or a Vested-style full company name
    // ("APPLE INC"). We resolve the latter via VESTED_NAME_TO_TICKER so that NetWorthRepository
    // can use asset.name as a valid Yahoo Finance symbol for live price refreshes.
    //
    // buyPrice may be blank (some IB positions) — stored as 0.0 (break-even).
    // currentValue is in USD and will be overwritten by the next live refresh anyway.
    private fun parseFormatC(cols: List<String>, now: Long): NetWorthAssetEntity? {
        if (cols.size < 4) return null
        val rawName  = cols[0].ifBlank { return null }.trim()
        val quantity = cols[1].toDoubleOrNull() ?: return null
        val buyPrice = cols[2].toDoubleOrNull() ?: 0.0
        val curValue = cols[3].toDoubleOrNull() ?: return null

        // Use known ticker if this looks like a Vested full-name; otherwise keep as-is.
        val symbol = VESTED_NAME_TO_TICKER[rawName.uppercase()] ?: rawName.uppercase()
        return buildEntity(symbol, quantity, buyPrice, curValue, "USD", AssetType.STOCK_US, now)
    }

    private fun buildEntity(
        name: String, quantity: Double, buyPrice: Double, curValue: Double,
        currency: String, assetType: AssetType, now: Long
    ) = NetWorthAssetEntity(
        id = 0,
        name = name,
        assetType = assetType,
        quantity = quantity,
        buyPrice = buyPrice,
        currentValue = curValue,
        currency = currency,
        notes = "",
        addedAt = now,
        updatedAt = now
    )

    // ── Format / delimiter detection ──────────────────────────────────────────

    private fun detectCsvDelimiter(headerLine: String): String = when {
        headerLine.contains('\t') -> "\t"
        headerLine.contains(',') -> ","
        else -> "\\s{2,}".toRegex().find(headerLine)?.value ?: " "
    }

    private fun detectFormat(headers: List<String>): BrokerFormat? {
        val joined = headers.joinToString("|")
        return when {
            joined.contains("stock name") || joined.contains("isin") -> BrokerFormat.FORMAT_A
            joined.contains("instrument") || joined.contains("avg. cost") ||
                joined.contains("cur. val") || joined.contains("qty.") -> BrokerFormat.FORMAT_B
            // Vested / Interactive Brokers: exact 4-column header
            headers.size >= 4 &&
                headers[0] == "name" &&
                headers[1] == "quantity" &&
                headers[2] == "buyprice" &&
                headers[3] == "currentvalue" -> BrokerFormat.FORMAT_C
            else -> null
        }
    }
}
