package app.trackone.data.repository

import android.content.Context
import android.net.Uri
import app.trackone.data.database.AssetType
import app.trackone.data.database.NetWorthAssetEntity
import app.trackone.data.database.NetWorthDao
import app.trackone.data.database.NetWorthTransactionDao
import app.trackone.data.database.NetWorthTransactionEntity
import app.trackone.data.database.TransactionType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

// ─── Broker guide — single source of truth for "which file do I export?" ─────
//
// Read by both the parser (for its "unrecognised format" error) and the
// Settings UI (for the "Which file should I upload?" help sheet), so the
// in-app copy can never drift from what the parser actually accepts.

data class BrokerGuideEntry(
    val broker: String,
    /** Where the export lives: "Web", "App", or "Client Portal". Shown as a small badge. */
    val source: String,
    /** Menu path to the report, e.g. "Portfolio → Holdings". No source/format prefix or suffix. */
    val path: String,
    /** File format to pick, e.g. "Excel" or "CSV". */
    val format: String,
    /** One short line of disambiguation — only what a first-time user would actually get wrong. */
    val note: String
) {
    /** Flattened "source: path (format)" form, for plain-text contexts (error messages). */
    val whereToExport: String get() = "$source: $path ($format)"
}

object BrokerGuide {
    val entries = listOf(
        BrokerGuideEntry(
            broker = "HDFC Securities",
            source = "Web",
            path = "Profile → Reports → Holding Statement",
            format = "Excel",
            note = "Not the contract note or tax P&L report — those skip quantity/avg price."
        ),
        BrokerGuideEntry(
            broker = "Angel One",
            source = "App",
            path = "Portfolio → Equity → download icon",
            format = "Excel",
            note = "Already includes ISIN, quantity, and average price."
        ),
        BrokerGuideEntry(
            broker = "Zerodha",
            source = "Web",
            path = "console.zerodha.com → Portfolio → Holdings → download icon",
            format = "CSV",
            note = "Use Console on the web — the Kite trading app has no export for this."
        ),
        BrokerGuideEntry(
            broker = "Groww",
            source = "App",
            path = "Profile → Reports → Holdings statement",
            format = "Excel",
            note = "Pick the holdings statement, not the P&L (tax) statement."
        ),
        BrokerGuideEntry(
            broker = "Vested",
            source = "App",
            path = "Transactions → pick date range → Export",
            format = "Excel",
            note = "Exports your full transaction history — this app reads the \"Trades\" tab inside it and nets buys/sells into current holdings."
        ),
        BrokerGuideEntry(
            broker = "Interactive Brokers",
            source = "Client Portal",
            path = "Performance & Reports → Activity Statements",
            format = "CSV",
            note = "Export the default Activity Statement — this app reads its \"Open Positions\" section directly."
        )
    )

    fun unrecognisedFormatMessage(): String {
        val supported = entries.joinToString("\n") { "  ${it.broker} — ${it.whereToExport}" }
        return "Unrecognised broker format.\n\n" +
            "Supported brokers:\n$supported\n\n" +
            "Make sure you're exporting the holdings/portfolio statement above, not a contract note, tax report, or PDF."
    }
}

// ─── Result types ──────────────────────────────────────────────────────────────

sealed class CsvImportResult {
    data class Success(val imported: Int, val skipped: Int) : CsvImportResult()
    data class Failure(val reason: String, val cause: Throwable? = null) : CsvImportResult()
}

// ─── Universal holding — the broker-agnostic canonical shape ──────────────────
//
// Every broker export (Indian or US, CSV/XLSX today) is normalized into this
// one shape before it ever touches persistence. Adding a new broker means
// writing one row-parser that emits a UniversalHolding — it never needs to
// know about NetWorthAssetEntity, currency handling, or AssetType mapping.
// See BrokerGuide (below BrokerCsvParser) for which exact report each broker
// must export, and why some (e.g. Vested) need a CSV/XLSX export rather than
// the PDF contract note/statement they show by default.

data class UniversalHolding(
    /** Tradable ticker (Yahoo Finance symbol) when resolvable — required for live price refresh. */
    val symbol: String,
    /** ISIN, when the broker's export includes one (most Indian brokers). Not all US brokers do. */
    val isin: String? = null,
    val quantity: Double,
    val avgBuyPrice: Double,
    val currentValue: Double,
    /** ISO 4217, e.g. "INR", "USD". */
    val currency: String,
    val assetType: AssetType,
    /** Which broker/format this row came from, e.g. "Zerodha / Groww". */
    val brokerSource: String
) {
    fun toEntity(now: Long): NetWorthAssetEntity = NetWorthAssetEntity(
        id = 0,
        name = symbol,
        assetType = assetType,
        quantity = quantity,
        buyPrice = avgBuyPrice,
        currentValue = currentValue,
        currency = currency,
        notes = "",
        addedAt = now,
        updatedAt = now,
        isin = isin,
        brokerSource = brokerSource
    )

    /**
     * A synthetic "position as of this import" lot — broker holdings statements only report
     * the current aggregate quantity/avg-price, not individual trade dates, so this is not a
     * real historical trade record. [assetId] is filled in only after the asset row exists.
     */
    fun toTransaction(assetId: Long, now: Long): NetWorthTransactionEntity = NetWorthTransactionEntity(
        assetId = assetId,
        symbol = symbol,
        assetType = assetType,
        transactionType = TransactionType.BUY,
        quantity = quantity,
        price = avgBuyPrice,
        currency = currency,
        transactionDate = now,
        isin = isin,
        brokerSource = brokerSource,
        notes = "Snapshot from broker holdings statement, not an individual trade date",
        createdAt = now
    )
}

// ─── Repository — file access, dedup, and persistence ─────────────────────────
//
// Parsing (format detection, XLSX/CSV reading, row parsing) lives entirely in
// BrokerCsvParser below and has no Context or DAO dependency. This class owns
// everything that does need Android framework types: reading the picked Uri,
// the SharedPreferences-backed dedup check, and the Room upsert.

@Singleton
class BrokerCsvRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val netWorthDao: NetWorthDao,
    private val netWorthTransactionDao: NetWorthTransactionDao
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
        private const val KEY_ORDER   = "imported_file_hashes_order"

        /** Caps how many past import hashes are retained, so this doesn't grow unbounded. */
        private const val MAX_TRACKED_HASHES = 200
    }

    /**
     * Parses a broker portfolio CSV/TSV/XLSX and upserts the parsed holdings
     * into the net-worth assets table. Existing assets with the same name+type
     * are updated in-place (quantity, buyPrice, currentValue) — never duplicated.
     *
     * A SHA-256 hash of the raw file bytes is stored in SharedPreferences after
     * a successful import. Re-uploading the exact same file is rejected instantly.
     *
     * @param onProgress called after each holding is persisted, with (holdings persisted so
     * far, total holdings) — lets the caller show a percentage during the DB-write phase.
     */
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

            when (val outcome = BrokerCsvParser.parse(fileBytes, isXlsx)) {
                is BrokerCsvParser.ParseOutcome.Failure -> CsvImportResult.Failure(outcome.reason)
                is BrokerCsvParser.ParseOutcome.Success -> {
                    persist(outcome.holdings, onProgress)
                    // Record hash only after a fully successful import.
                    recordImportHash(fileHash)
                    CsvImportResult.Success(imported = outcome.holdings.size, skipped = outcome.skipped)
                }
            }
        } catch (e: Exception) {
            CsvImportResult.Failure("Import failed: ${e.message}", e)
        }
    }

    // ── Persist ──────────────────────────────────────────────────────────────

    /**
     * For each parsed holding: update the existing asset row if name+type matches,
     * otherwise insert as a new one. This prevents duplicate rows on re-import of
     * an updated broker export. Each import also records a transaction snapshot
     * (see [UniversalHolding.toTransaction]) so the holding's history isn't lost
     * on the next re-import overwriting its current quantity/price.
     */
    private suspend fun persist(holdings: List<UniversalHolding>, onProgress: (Int, Int) -> Unit) {
        val now = System.currentTimeMillis()
        for ((index, holding) in holdings.withIndex()) {
            val entity = holding.toEntity(now)
            val existing = netWorthDao.findAssetByNameAndType(entity.name, entity.assetType)
            val assetId = if (existing != null) {
                netWorthDao.updateAsset(
                    existing.copy(
                        quantity     = entity.quantity,
                        buyPrice     = entity.buyPrice,
                        currentValue = entity.currentValue,
                        updatedAt    = entity.updatedAt,
                        isin         = entity.isin,
                        brokerSource = entity.brokerSource
                    )
                )
                existing.id
            } else {
                netWorthDao.insertAsset(entity)
            }
            netWorthTransactionDao.insert(holding.toTransaction(assetId, now))
            onProgress(index + 1, holdings.size)
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

    /**
     * Forgets every previously-imported file hash. Called from [LocalDataRepository] when the
     * user clears local data — otherwise re-uploading the same broker file after a clear would
     * still be rejected as "already imported", even though the data it would restore is gone.
     */
    fun clearImportHistory() {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }

    /** Stored as a bounded set — once [MAX_TRACKED_HASHES] is exceeded, the oldest-recorded
     *  hashes (tracked via insertion order in [KEY_ORDER]) are dropped so this can't grow
     *  unbounded across years of imports. */
    private fun recordImportHash(hash: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val order = (prefs.getString(KEY_ORDER, null)?.split(",")?.filter { it.isNotBlank() }
            ?: emptyList()).toMutableList()
        order.remove(hash)
        order.add(hash)
        while (order.size > MAX_TRACKED_HASHES) order.removeAt(0)

        prefs.edit()
            .putStringSet(KEY_HASHES, order.toSet())
            .putString(KEY_ORDER, order.joinToString(","))
            .apply()
    }
}

// ─── Parser — pure: no Context, no DAO, no SharedPreferences ──────────────────
//
// Takes raw file bytes in, returns parsed entities out. Testable with plain
// byte arrays and no Android framework types.

private object BrokerCsvParser {

    sealed class ParseOutcome {
        data class Success(val holdings: List<UniversalHolding>, val skipped: Int) : ParseOutcome()
        data class Failure(val reason: String) : ParseOutcome()
    }

    private enum class BrokerFormat {
        /**
         * HDFC Securities / Angel One / similar Indian brokers.
         * Report to export: the **Holding Statement** (HDFC Securities: Profile →
         * Reports → Holding Statement, Excel; Angel One: Portfolio → Equity →
         * download icon) — NOT the contract note or tax P&L report, which don't
         * carry live quantity/avg-price columns.
         * Columns: Stock Name | ISIN | Quantity | Average buy price | Buy value |
         *          Closing price | Closing value | Unrealised P&L
         */
        FORMAT_A,

        /**
         * Zerodha / Groww / similar Indian brokers.
         * Report to export: Zerodha — Console (console.zerodha.com) → Portfolio →
         * Holdings → download icon → CSV. Groww — Profile → Reports → Groww
         * Balance/Holdings statement → Excel.
         * Columns: Instrument | Qty. | Avg. cost | LTP | Invested | Cur. val | P&L | Net chg.
         */
        FORMAT_B,

        /**
         * Vested / Interactive Brokers / similar US brokers.
         * Report to export: Vested's default in-app statement is a PDF, which this
         * parser cannot read — from the app, use Transactions → pick date range →
         * Export → CSV instead. Interactive Brokers — Client Portal → Performance
         * & Reports → Activity Statements → format CSV (Flex Query for full detail).
         * Columns: name | quantity | buyPrice | currentValue
         * buyPrice may be blank — treated as 0.0 (break-even).
         */
        FORMAT_C,

        /**
         * Zerodha Console's actual "Holdings" export (as of 2026). Its header also
         * contains "isin", so this MUST be detected before the generic Format A
         * check below, or every row gets misread through Format A's column layout.
         * Columns: (blank) | Symbol | ISIN | Sector | Quantity Available |
         *          Quantity Discrepant | Quantity Long Term |
         *          Quantity Pledged (Margin) | Quantity Pledged (Loan) |
         *          Average Price | Previous Closing Price | Unrealized P&L |
         *          Unrealized P&L Pct.
         * No direct "current value" column — derived as quantity * previous closing price.
         */
        FORMAT_ZERODHA,

        /**
         * Vested's "All Transactions" export (App → Transactions → Export) is a
         * multi-sheet workbook with no standalone holdings snapshot — the closest
         * thing is the "Trades" sheet, a per-trade log. This format nets every
         * buy/sell per ticker into a current quantity + weighted-average cost.
         * Columns: Date | Time (in UTC) | Name | Ticker | Activity | Order Type |
         *          Quantity | Price Per Share (in USD) | Cash Amount (in USD) |
         *          Commission Charges (in USD)
         */
        FORMAT_VESTED_TRADES,

        /**
         * Interactive Brokers' Activity Statement CSV is not one table — it's dozens
         * of sections concatenated in one file, each row prefixed with its section
         * name and a Header/Data marker. This format extracts only the
         * "Open Positions" section.
         * Columns (after the section-name/marker prefix): DataDiscriminator |
         *          Asset Category | Currency | Symbol | Quantity | Mult | Cost Price |
         *          Cost Basis | Close Price | Value | Unrealized P/L | Code
         */
        FORMAT_IB_POSITIONS
    }

    private data class RawRow(val cols: List<String>)

    /**
     * Maps Vested's full company-name strings (as exported in the `name` column)
     * to the Yahoo Finance ticker symbol used by NetWorthRepository for live price lookups.
     * Interactive Brokers already exports tickers, so they pass through unchanged.
     */
    private val VESTED_NAME_TO_TICKER = mapOf(
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
        "EATON VANCE FLOATING RATE TR"       to "EFT",
        "DOCUSIGN INC"                       to "DOCU",
        "PALANTIR TECHNOLOGIES INC"          to "PLTR",
        "PANW"                               to "PANW",
        "PALO ALTO NETWORKS INC"             to "PANW"
    )

    /**
     * A workbook/file can hold more than one candidate table: an XLSX may have
     * several sheets (only one of which is actually holdings/trades data — see
     * Vested's multi-sheet export), and Interactive Brokers packs dozens of
     * tables into a single CSV (see [parseIbActivityStatement]). Each candidate
     * is tried in turn; the first one whose header is recognised AND yields at
     * least one holding wins.
     */
    fun parse(fileBytes: ByteArray, isXlsx: Boolean): ParseOutcome {
        val candidates: List<Pair<List<String>, List<RawRow>>> = if (isXlsx) {
            parseXlsxSheets(fileBytes)
        } else {
            val raw = fileBytes.toString(Charsets.UTF_8)
            listOfNotNull(parseIbActivityStatement(raw), parseCsv(fileBytes.inputStream()))
        }

        if (candidates.isEmpty()) {
            return ParseOutcome.Failure(
                "The file has no data rows. Make sure you exported the portfolio holdings from your broker."
            )
        }

        var anyFormatDetected = false
        for ((header, rows) in candidates) {
            val format = detectFormat(header) ?: continue
            anyFormatDetected = true

            val (holdings, skipped) = when (format) {
                BrokerFormat.FORMAT_A -> parseRows(rows, ::parseFormatA)
                BrokerFormat.FORMAT_B -> parseRows(rows, ::parseFormatB)
                BrokerFormat.FORMAT_C -> parseRows(rows, ::parseFormatC)
                BrokerFormat.FORMAT_ZERODHA -> parseRows(rows) { parseFormatZerodha(header, it) }
                BrokerFormat.FORMAT_IB_POSITIONS -> parseRows(rows) { parseFormatIbPositions(header, it) }
                BrokerFormat.FORMAT_VESTED_TRADES -> aggregateVestedTrades(header, rows)
            }

            if (holdings.isNotEmpty()) {
                return ParseOutcome.Success(holdings, skipped)
            }
        }

        return if (anyFormatDetected) {
            ParseOutcome.Failure(
                "No valid holdings found in the file. The file may be empty or the columns could not be parsed."
            )
        } else {
            ParseOutcome.Failure(BrokerGuide.unrecognisedFormatMessage())
        }
    }

    /** Runs a per-row parser over every row, returning the parsed holdings plus a skip count. */
    private inline fun parseRows(
        rows: List<RawRow>,
        rowParser: (List<String>) -> UniversalHolding?
    ): Pair<List<UniversalHolding>, Int> {
        var skipped = 0
        val holdings = mutableListOf<UniversalHolding>()
        for (row in rows) {
            val holding = rowParser(row.cols)
            if (holding != null) holdings.add(holding) else skipped++
        }
        return holdings to skipped
    }

    // ── XLSX parser ───────────────────────────────────────────────────────────
    //
    // XLSX is a ZIP archive of XML files. No third-party library needed — we
    // use Android's built-in ZipInputStream and XmlPullParser (android.util.Xml).
    //
    //  xl/sharedStrings.xml  ->  string table; cells of type "s" point here by index
    //  xl/worksheets/sheet1.xml  ->  the actual grid rows/cells

    /**
     * Accepts already-buffered bytes so the caller can hash the file before parsing.
     * Returns one (header, rows) candidate per sheet in the workbook — a workbook
     * like Vested's "All Transactions" export has several sheets, and only one of
     * them (Trades) carries data this parser recognises. [parse] tries each in turn.
     */
    private fun parseXlsxSheets(bytes: ByteArray): List<Pair<List<String>, List<RawRow>>> {
        val sharedStrings = readSharedStrings(bytes)
        return readAllSheetRows(bytes, sharedStrings).mapNotNull { rows -> toHeaderAndRows(rows) }
    }

    /** The first row with >= 4 non-blank cells is treated as a sheet's header. */
    private fun toHeaderAndRows(rows: List<List<String>>): Pair<List<String>, List<RawRow>>? {
        if (rows.size < 2) return null

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

    /**
     * Parses every xl/worksheets/sheet*.xml into a list-of-rows (each row = list of cell
     * strings), one entry per sheet. Earlier versions stopped at the first matching sheet
     * entry — but the first sheet in the zip isn't necessarily the one with useful data
     * (e.g. Vested's export puts an "My Account" info sheet before "Trades").
     */
    private fun readAllSheetRows(
        xlsxBytes: ByteArray,
        sharedStrings: List<String>
    ): List<List<List<String>>> {
        val sheets = mutableListOf<List<List<String>>>()
        ZipInputStream(xlsxBytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name.matches(Regex("xl/worksheets/sheet\\d+\\.xml"))) {
                    val result = mutableListOf<List<String>>()
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
                                    // Cells with a missing/blank "r" attribute (some exporters
                                    // omit it) resolve to -1 — fall back to the next sequential
                                    // column instead of indexing the row array out of bounds.
                                    val colIdx = colRefToIndex(cellRef).let {
                                        if (it < 0) currentRow.size else it
                                    }
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
                    sheets.add(result)
                }
                entry = zip.nextEntry
            }
        }
        return sheets
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

    /**
     * Interactive Brokers' Activity Statement CSV isn't one table — it's dozens of
     * sections concatenated into one file. Every line is prefixed with its section
     * name and a "Header"/"Data" marker, e.g.:
     *   Open Positions,Header,DataDiscriminator,Asset Category,Currency,Symbol,...
     *   Open Positions,Data,Summary,Stocks,USD,AAPL,...
     * This extracts just the "Open Positions" section. Returns null for any file
     * that isn't this format (e.g. a plain single-table CSV), so it's safe to try
     * unconditionally before falling back to [parseCsv].
     */
    private fun parseIbActivityStatement(raw: String): Pair<List<String>, List<RawRow>>? {
        val lines = raw.lines().map { it.trim().removePrefix("﻿") }.filter { it.isNotBlank() }

        var header: List<String>? = null
        val rows = mutableListOf<RawRow>()
        for (line in lines) {
            val cols = splitCsvLine(line)
            if (cols.size < 3) continue
            if (!cols[0].trim().equals("Open Positions", ignoreCase = true)) continue

            val rest = cols.drop(2).map { it.trim() }
            when (cols[1].trim()) {
                "Header" -> header = rest.map { it.lowercase() }
                "Data" -> if (header != null) rows.add(RawRow(rest))
            }
        }

        val h = header ?: return null
        if (rows.isEmpty()) return null
        return Pair(h, rows)
    }

    /**
     * Splits one CSV line on [delimiter], honouring double-quoted fields (which may
     * contain the delimiter itself, e.g. IB's `"Two Pickwick Plaza, Greenwich, CT 06830"`).
     * The plain `line.split(delimiter)` used elsewhere in this file is fine for the
     * simpler broker exports, which never quote fields, but breaks on IB's file.
     */
    private fun splitCsvLine(line: String, delimiter: Char = ','): List<String> {
        val result = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> {
                    field.append('"'); i++ // escaped quote
                }
                c == '"' -> inQuotes = !inQuotes
                c == delimiter && !inQuotes -> { result.add(field.toString()); field.clear() }
                else -> field.append(c)
            }
            i++
        }
        result.add(field.toString())
        return result
    }

    // ── Row parsers ───────────────────────────────────────────────────────────

    // Format A: Stock Name | ISIN | Qty | Avg Buy Price | Buy Value | Closing Price | Closing Value | P&L
    // (HDFC Securities / Angel One "Holding Statement" — see BrokerFormat.FORMAT_A doc.)
    private fun parseFormatA(cols: List<String>): UniversalHolding? {
        if (cols.size < 7) return null
        val name = cols[0].ifBlank { return null }
        val isin = cols[1].ifBlank { null }
        val quantity = cols[2].toDoubleOrNull() ?: return null
        val buyPrice = cols[3].toDoubleOrNull() ?: return null
        val curValue = cols[6].toDoubleOrNull() ?: return null
        return UniversalHolding(
            symbol = name.trim(), isin = isin, quantity = quantity, avgBuyPrice = buyPrice,
            currentValue = curValue, currency = "INR", assetType = AssetType.STOCK_IN,
            brokerSource = "HDFC Securities / Angel One"
        )
    }

    // Format B: Instrument | Qty. | Avg. cost | LTP | Invested | Cur. val | P&L | Net chg.
    // (Zerodha Console / Groww "Holdings" export — see BrokerFormat.FORMAT_B doc.)
    private fun parseFormatB(cols: List<String>): UniversalHolding? {
        if (cols.size < 6) return null
        val symbol = cols[0].ifBlank { return null }
        val quantity = cols[1].toDoubleOrNull() ?: return null
        val buyPrice = cols[2].toDoubleOrNull() ?: return null
        val curValue = cols[5].toDoubleOrNull() ?: return null
        return UniversalHolding(
            symbol = symbol.trim().uppercase(), quantity = quantity, avgBuyPrice = buyPrice,
            currentValue = curValue, currency = "INR", assetType = AssetType.STOCK_IN,
            brokerSource = "Zerodha / Groww"
        )
    }

    // Format C: name | quantity | buyPrice | currentValue  (Vested / Interactive Brokers — US stocks)
    //
    // The `name` column may be either a ticker ("AMD") or a Vested-style full company name
    // ("APPLE INC"). We resolve the latter via VESTED_NAME_TO_TICKER so that NetWorthRepository
    // can use asset.name as a valid Yahoo Finance symbol for live price refreshes.
    //
    // buyPrice may be blank (some IB positions) — stored as 0.0 (break-even).
    // currentValue is in USD and will be overwritten by the next live refresh anyway.
    // (This needs Vested's CSV transaction export, not its default PDF statement —
    //  see BrokerFormat.FORMAT_C doc.)
    private fun parseFormatC(cols: List<String>): UniversalHolding? {
        if (cols.size < 4) return null
        val rawName  = cols[0].ifBlank { return null }.trim()
        val quantity = cols[1].toDoubleOrNull() ?: return null
        val buyPrice = cols[2].toDoubleOrNull() ?: 0.0
        val curValue = cols[3].toDoubleOrNull() ?: return null

        // Use known ticker if this looks like a Vested full-name; otherwise keep as-is.
        val symbol = VESTED_NAME_TO_TICKER[rawName.uppercase()] ?: rawName.uppercase()
        return UniversalHolding(
            symbol = symbol, quantity = quantity, avgBuyPrice = buyPrice,
            currentValue = curValue, currency = "USD", assetType = AssetType.STOCK_US,
            brokerSource = "Vested / Interactive Brokers"
        )
    }

    // Format Zerodha: (blank) | Symbol | ISIN | Sector | Quantity Available | ... |
    // Average Price | Previous Closing Price | ...  (real Console "Holdings" export —
    // see BrokerFormat.FORMAT_ZERODHA doc.) Columns are looked up by header name rather
    // than fixed position, since the leading blank column shifts every index by one.
    private fun parseFormatZerodha(header: List<String>, cols: List<String>): UniversalHolding? {
        val symbolIdx = header.indexOf("symbol")
        val isinIdx = header.indexOf("isin")
        val qtyIdx = header.indexOf("quantity available")
        val avgIdx = header.indexOf("average price")
        val closeIdx = header.indexOf("previous closing price")
        if (symbolIdx < 0 || qtyIdx < 0 || avgIdx < 0 || closeIdx < 0) return null
        if (cols.size <= maxOf(symbolIdx, qtyIdx, avgIdx, closeIdx)) return null

        val symbol = cols[symbolIdx].ifBlank { return null }
        val isin = isinIdx.takeIf { it >= 0 }?.let { cols.getOrNull(it) }?.ifBlank { null }
        val quantity = cols[qtyIdx].toDoubleOrNull() ?: return null
        val avgPrice = cols[avgIdx].toDoubleOrNull() ?: return null
        val closePrice = cols[closeIdx].toDoubleOrNull() ?: return null

        // No direct "current value" column in this export — derive it.
        return UniversalHolding(
            symbol = symbol.trim().uppercase(), isin = isin, quantity = quantity, avgBuyPrice = avgPrice,
            currentValue = quantity * closePrice, currency = "INR", assetType = AssetType.STOCK_IN,
            brokerSource = "Zerodha"
        )
    }

    // Format IB Positions: DataDiscriminator | Asset Category | Currency | Symbol | Quantity |
    // Mult | Cost Price | Cost Basis | Close Price | Value | Unrealized P/L | Code
    // (the "Open Positions" section of IB's Activity Statement — see
    // BrokerFormat.FORMAT_IB_POSITIONS doc and [parseIbActivityStatement].)
    private fun parseFormatIbPositions(header: List<String>, cols: List<String>): UniversalHolding? {
        val symbolIdx = header.indexOf("symbol")
        val qtyIdx = header.indexOf("quantity")
        val costPriceIdx = header.indexOf("cost price")
        val valueIdx = header.indexOf("value")
        val currencyIdx = header.indexOf("currency")
        if (symbolIdx < 0 || qtyIdx < 0 || costPriceIdx < 0 || valueIdx < 0) return null
        if (cols.size <= maxOf(symbolIdx, qtyIdx, costPriceIdx, valueIdx)) return null

        val symbol = cols[symbolIdx].ifBlank { return null }
        val quantity = cols[qtyIdx].toDoubleOrNull() ?: return null
        val costPrice = cols[costPriceIdx].toDoubleOrNull() ?: return null
        val value = cols[valueIdx].toDoubleOrNull() ?: return null
        val currency = currencyIdx.takeIf { it >= 0 }?.let { cols.getOrNull(it) }?.ifBlank { null } ?: "USD"

        return UniversalHolding(
            symbol = symbol.trim().uppercase(), quantity = quantity, avgBuyPrice = costPrice,
            currentValue = value, currency = currency, assetType = AssetType.STOCK_US,
            brokerSource = "Interactive Brokers"
        )
    }

    // Format Vested Trades: Date | Time (in UTC) | Name | Ticker | Activity | Order Type |
    // Quantity | Price Per Share (in USD) | Cash Amount (in USD) | Commission Charges (in USD)
    // (Vested's "Trades" sheet inside its "All Transactions" export — see
    // BrokerFormat.FORMAT_VESTED_TRADES doc.)
    //
    // Unlike the other formats, this is a per-trade log, not a holdings snapshot — every
    // buy/sell for a ticker is netted into one current quantity + weighted-average cost.
    // Fractional-share buys/sells are common in Vested exports and are handled the same
    // way as whole shares. currentValue is set to quantity * avgBuyPrice (break-even) and
    // will be overwritten by the next live price refresh, same convention as Format C.
    private fun aggregateVestedTrades(header: List<String>, rows: List<RawRow>): Pair<List<UniversalHolding>, Int> {
        val tickerIdx = header.indexOf("ticker")
        val activityIdx = header.indexOf("activity")
        val qtyIdx = header.indexOf("quantity")
        val priceIdx = header.indexOf("price per share (in usd)")
        if (tickerIdx < 0 || activityIdx < 0 || qtyIdx < 0 || priceIdx < 0) {
            return emptyList<UniversalHolding>() to rows.size
        }

        class Position(var quantity: Double = 0.0, var costBasis: Double = 0.0)
        val byTicker = linkedMapOf<String, Position>()
        var skipped = 0

        for (row in rows) {
            val cols = row.cols
            val ticker = cols.getOrNull(tickerIdx)?.trim()?.uppercase()
            val activity = cols.getOrNull(activityIdx)?.trim()?.lowercase()
            val qty = cols.getOrNull(qtyIdx)?.toDoubleOrNull()
            val price = cols.getOrNull(priceIdx)?.toDoubleOrNull()
            if (ticker.isNullOrBlank() || activity == null || qty == null || price == null) {
                skipped++
                continue
            }
            val position = byTicker.getOrPut(ticker) { Position() }
            when (activity) {
                "buy" -> { position.quantity += qty; position.costBasis += qty * price }
                "sell" -> { position.quantity -= qty; position.costBasis -= qty * price }
                else -> skipped++ // dividends, fees, etc. — not a position change
            }
        }

        val holdings = byTicker.mapNotNull { (ticker, position) ->
            // A fully-exited position (or a rounding artifact) nets to ~0 — drop it
            // rather than showing a phantom holding.
            if (position.quantity <= 0.0) return@mapNotNull null
            val avgPrice = position.costBasis / position.quantity
            UniversalHolding(
                symbol = ticker, quantity = position.quantity, avgBuyPrice = avgPrice,
                currentValue = position.quantity * avgPrice, currency = "USD", assetType = AssetType.STOCK_US,
                brokerSource = "Vested"
            )
        }
        return holdings to skipped
    }

    // ── Format / delimiter detection ──────────────────────────────────────────

    private fun detectCsvDelimiter(headerLine: String): String = when {
        headerLine.contains('\t') -> "\t"
        headerLine.contains(',') -> ","
        else -> "\\s{2,}".toRegex().find(headerLine)?.value ?: " "
    }

    private fun detectFormat(headers: List<String>): BrokerFormat? {
        val joined = headers.joinToString("|")
        return when {
            // These three checks must come first: each header also contains a substring
            // ("isin", generic "quantity"/"symbol") that the older, looser checks below
            // would otherwise misclassify — see each FORMAT_* doc comment for the real
            // header this was reverse-engineered from.
            joined.contains("quantity available") || joined.contains("previous closing price") ->
                BrokerFormat.FORMAT_ZERODHA
            joined.contains("datadiscriminator") && joined.contains("cost basis") ->
                BrokerFormat.FORMAT_IB_POSITIONS
            joined.contains("ticker") && joined.contains("order type") ->
                BrokerFormat.FORMAT_VESTED_TRADES

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
