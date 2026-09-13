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

        repository = BrokerCsvRepository(context, netWorthDao, netWorthTransactionDao)
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
}
