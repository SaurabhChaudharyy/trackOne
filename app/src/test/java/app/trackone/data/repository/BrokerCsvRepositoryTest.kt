package app.trackone.data.repository

import android.content.ContentResolver
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import app.trackone.data.database.AssetType
import app.trackone.data.database.NetWorthAssetEntity
import app.trackone.data.database.NetWorthDao
import app.trackone.data.database.NetWorthTransactionDao
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.MessageDigest

class BrokerCsvRepositoryTest {

    private lateinit var context: Context
    private lateinit var contentResolver: ContentResolver
    private lateinit var netWorthDao: NetWorthDao
    private lateinit var netWorthTransactionDao: NetWorthTransactionDao
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private lateinit var symbolResolver: SymbolResolver
    private lateinit var repository: BrokerCsvRepository
    private lateinit var uri: Uri

    // A minimal, valid Format B (Zerodha/Groww) CSV — one holding, one row.
    private val validCsvBytes =
        "Instrument,Qty.,Avg. cost,LTP,Invested,Cur. val,P&L,Net chg.\nINFY,5,1500.0,1600.0,7500.0,8000.0,500.0,6.67\n"
            .toByteArray()

    @Before
    fun setUp() {
        contentResolver = mockk()
        every { contentResolver.getType(any()) } returns "text/csv"

        editor = mockk(relaxed = true)
        sharedPreferences = mockk {
            every { getStringSet(any(), any()) } returns emptySet()
            every { getString(any(), any()) } returns null
            every { edit() } returns editor
        }
        every { editor.putStringSet(any(), any()) } returns editor
        every { editor.putString(any(), any()) } returns editor

        context = mockk()
        every { context.contentResolver } returns contentResolver
        every { context.getSharedPreferences(any(), any()) } returns sharedPreferences

        netWorthDao = mockk()
        netWorthTransactionDao = mockk(relaxed = true)

        uri = mockk {
            every { lastPathSegment } returns "holdings.csv"
        }

        symbolResolver = mockk()
        coEvery { symbolResolver.resolve(any(), any(), any(), any()) } returns SymbolResolution.NotFound

        repository = BrokerCsvRepository(context, netWorthDao, netWorthTransactionDao, symbolResolver)
    }

    private fun stubFileContents(bytes: ByteArray) {
        every { contentResolver.openInputStream(uri) } returns bytes.inputStream()
    }

    @Test
    fun `a recognised CSV with a brand-new holding is inserted, not updated`() = runTest {
        stubFileContents(validCsvBytes)
        coEvery { netWorthDao.findAssetByNameAndType("INFY", AssetType.STOCK_IN) } returns null
        coEvery { netWorthDao.insertAsset(any()) } returns 42L

        val result = repository.importFromUri(uri)

        assertTrue(result is CsvImportResult.Success)
        assertEquals(1, (result as CsvImportResult.Success).imported)
        coVerify(exactly = 1) { netWorthDao.insertAsset(any()) }
        coVerify(exactly = 0) { netWorthDao.updateAsset(any()) }
        coVerify(exactly = 1) { netWorthTransactionDao.insert(match { it.assetId == 42L && it.symbol == "INFY" }) }
    }

    @Test
    fun `re-importing a holding that already exists updates it in place`() = runTest {
        stubFileContents(validCsvBytes)
        val existing = NetWorthAssetEntity(
            id = 7,
            name = "INFY",
            assetType = AssetType.STOCK_IN,
            quantity = 1.0,
            buyPrice = 1000.0,
            currentValue = 1000.0
        )
        coEvery { netWorthDao.findAssetByNameAndType("INFY", AssetType.STOCK_IN) } returns existing
        coEvery { netWorthDao.updateAsset(any()) } returns Unit

        val result = repository.importFromUri(uri)

        assertTrue(result is CsvImportResult.Success)
        coVerify(exactly = 0) { netWorthDao.insertAsset(any()) }
        coVerify(exactly = 1) {
            netWorthDao.updateAsset(match { it.id == 7L && it.quantity == 5.0 && it.buyPrice == 1500.0 })
        }
        coVerify(exactly = 1) { netWorthTransactionDao.insert(match { it.assetId == 7L }) }
    }

    @Test
    fun `re-importing a USD holding over an already-refreshed INR row keeps buyPrice and currency consistent`() = runTest {
        // After the first live refresh NetWorthRepository rewrites this row to INR. A later
        // re-import of the USD broker file overwrites buyPrice/currentValue with USD figures;
        // if `currency` isn't reset to match, the next refresh sees currency == "INR", skips the
        // buyPrice USD->INR conversion, and pairs an INR currentValue with a USD buyPrice
        // (~88x fake gain in the portfolio digest).
        stubFileContents("name,quantity,buyprice,currentvalue\nDOCN,10,14.0,140.0\n".toByteArray())
        val refreshedExisting = NetWorthAssetEntity(
            id = 9,
            name = "DOCN",
            assetType = AssetType.STOCK_US,
            quantity = 10.0,
            buyPrice = 1232.0,       // already converted to INR
            currentValue = 3520.0,
            currency = "INR"
        )
        coEvery { netWorthDao.findAssetByNameAndType("DOCN", AssetType.STOCK_US) } returns refreshedExisting
        val updated = slot<NetWorthAssetEntity>()
        coEvery { netWorthDao.updateAsset(capture(updated)) } returns Unit

        repository.importFromUri(uri)

        assertEquals(14.0, updated.captured.buyPrice, 0.0)   // written in USD...
        assertEquals("USD", updated.captured.currency)        // ...so it must be labelled USD
    }

    @Test
    fun `an unrecognised file format fails without touching the database`() = runTest {
        stubFileContents("some,random,header\n1,2,3\n".toByteArray())

        val result = repository.importFromUri(uri)

        assertTrue(result is CsvImportResult.Failure)
        coVerify(exactly = 0) { netWorthDao.insertAsset(any()) }
    }

    @Test
    fun `re-uploading the exact same file is rejected as a duplicate`() = runTest {
        stubFileContents(validCsvBytes)
        val hash = MessageDigest.getInstance("SHA-256").digest(validCsvBytes).joinToString("") { "%02x".format(it) }
        every { sharedPreferences.getStringSet(any(), any()) } returns setOf(hash)

        val result = repository.importFromUri(uri)

        assertTrue(result is CsvImportResult.Failure)
        assertTrue((result as CsvImportResult.Failure).reason.contains("already been imported"))
        coVerify(exactly = 0) { netWorthDao.insertAsset(any()) }
    }

    @Test
    fun `a successful import records the file hash so a re-upload is later rejected`() = runTest {
        stubFileContents(validCsvBytes)
        coEvery { netWorthDao.findAssetByNameAndType(any(), any()) } returns null
        coEvery { netWorthDao.insertAsset(any()) } returns 1L
        val recordedHashes = slot<Set<String>>()
        every { editor.putStringSet(any(), capture(recordedHashes)) } returns editor

        repository.importFromUri(uri)

        val expectedHash = MessageDigest.getInstance("SHA-256").digest(validCsvBytes).joinToString("") { "%02x".format(it) }
        assertTrue(recordedHashes.captured.contains(expectedHash))
    }

    @Test
    fun `a missing file is a clean failure, not a crash`() = runTest {
        every { contentResolver.openInputStream(uri) } returns null

        val result = repository.importFromUri(uri)

        assertTrue(result is CsvImportResult.Failure)
    }

    @Test
    fun `clearImportHistory wipes the dedup preference store`() {
        repository.clearImportHistory()

        io.mockk.verify { editor.clear() }
    }

    // ── company names are resolved to tickers at import ─────────────────────

    // HDFC Securities / Angel One "Holding Statement": Stock Name | ISIN | Qty | Avg Buy | Buy Value | Close | Close Value | P&L
    private val formatACsv = (
        "Stock Name,ISIN,Quantity,Average Buy Price,Buy Value,Closing Price,Closing Value,Unrealised P&L\n" +
        "ITC HOTELS LIMITED,INE379A01028,10,200.0,2000.0,210.0,2100.0,100.0\n"
    ).toByteArray()

    @Test
    fun `a company name from a broker statement is stored as its ticker, not the name`() = runTest {
        stubFileContents(formatACsv)
        coEvery { symbolResolver.resolve("ITC HOTELS LIMITED", "INE379A01028", AssetType.STOCK_IN, any()) } returns
            SymbolResolution.Confident("ITCHOTELS", "ITC Hotels Limited", MatchBasis.ISIN)
        coEvery { netWorthDao.findAssetByNameAndType(any(), any()) } returns null
        val inserted = slot<NetWorthAssetEntity>()
        coEvery { netWorthDao.insertAsset(capture(inserted)) } returns 1L

        repository.importFromUri(uri)

        assertEquals("ITCHOTELS", inserted.captured.name)
        assertEquals("INE379A01028", inserted.captured.isin)     // still kept
        coVerify { netWorthTransactionDao.insert(match { it.symbol == "ITCHOTELS" }) }
    }

    @Test
    fun `an unresolvable name is stored as before, never guessed or dropped`() = runTest {
        stubFileContents(formatACsv)      // resolver stub defaults to NotFound
        coEvery { netWorthDao.findAssetByNameAndType(any(), any()) } returns null
        val inserted = slot<NetWorthAssetEntity>()
        coEvery { netWorthDao.insertAsset(capture(inserted)) } returns 1L

        val result = repository.importFromUri(uri)

        assertTrue(result is CsvImportResult.Success)
        assertEquals("ITC HOTELS LIMITED", inserted.captured.name)
    }

    @Test
    fun `an ambiguous name is stored as before rather than picking one`() = runTest {
        stubFileContents(formatACsv)
        coEvery { symbolResolver.resolve(any(), any(), any(), any()) } returns SymbolResolution.Ambiguous(listOf("GOOG", "GOOGL"))
        coEvery { netWorthDao.findAssetByNameAndType(any(), any()) } returns null
        val inserted = slot<NetWorthAssetEntity>()
        coEvery { netWorthDao.insertAsset(capture(inserted)) } returns 1L

        repository.importFromUri(uri)

        assertEquals("ITC HOTELS LIMITED", inserted.captured.name)
    }

    @Test
    fun `rows that already carry a ticker never trigger a lookup`() = runTest {
        stubFileContents(validCsvBytes)   // Zerodha/Groww style: the Instrument column is the ticker
        coEvery { netWorthDao.findAssetByNameAndType(any(), any()) } returns null
        coEvery { netWorthDao.insertAsset(any()) } returns 1L

        repository.importFromUri(uri)

        coVerify(exactly = 0) { symbolResolver.resolve(any(), any(), any(), any()) }
    }
}
