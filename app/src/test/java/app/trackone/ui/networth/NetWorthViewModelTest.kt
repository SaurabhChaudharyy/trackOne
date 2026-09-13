package app.trackone.ui.networth

import androidx.lifecycle.LiveData
import app.trackone.data.database.AssetType
import app.trackone.data.database.NetWorthAssetEntity
import app.trackone.data.database.NetWorthDao
import app.trackone.data.repository.NetWorthRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NetWorthViewModelTest {

    private lateinit var netWorthDao: NetWorthDao
    private lateinit var netWorthRepository: NetWorthRepository
    private lateinit var viewModel: NetWorthViewModel
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)

        netWorthDao = mockk()
        netWorthRepository = mockk()
        every { netWorthDao.getAllAssets() } returns mockk<LiveData<List<NetWorthAssetEntity>>>(relaxed = true)
        every { netWorthDao.getTotalNetWorth() } returns mockk<LiveData<Double?>>(relaxed = true)

        viewModel = NetWorthViewModel(netWorthDao, netWorthRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun asset(buyPrice: Double, quantity: Double = 1.0, currentValue: Double = 100.0) =
        NetWorthAssetEntity(
            name = "GOLD-SAVINGS",
            assetType = AssetType.GOLD,
            quantity = quantity,
            buyPrice = buyPrice,
            currentValue = currentValue
        )

    @Test
    fun `an asset with a known buy price is always inserted as a new lot, never merged`() = runTest {
        val newAsset = asset(buyPrice = 5000.0)
        coEvery { netWorthDao.insertAsset(newAsset) } returns 1L

        viewModel.addOrMergeAsset(newAsset)
        dispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { netWorthDao.insertAsset(newAsset) }
        coVerify(exactly = 0) { netWorthDao.findMergeCandidate(any(), any()) }
    }

    @Test
    fun `an asset with no buy price and no existing match is inserted fresh`() = runTest {
        val newAsset = asset(buyPrice = 0.0)
        coEvery { netWorthDao.findMergeCandidate("GOLD-SAVINGS", AssetType.GOLD) } returns null
        coEvery { netWorthDao.insertAsset(newAsset) } returns 1L

        viewModel.addOrMergeAsset(newAsset)
        dispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { netWorthDao.insertAsset(newAsset) }
    }

    @Test
    fun `an asset with no buy price merges into an existing no-buy-price entry`() = runTest {
        val existing = NetWorthAssetEntity(
            id = 9,
            name = "GOLD-SAVINGS",
            assetType = AssetType.GOLD,
            quantity = 10.0,
            buyPrice = 0.0,
            currentValue = 1000.0
        )
        val addition = asset(buyPrice = 0.0, quantity = 5.0, currentValue = 500.0)
        coEvery { netWorthDao.findMergeCandidate("GOLD-SAVINGS", AssetType.GOLD) } returns existing
        coEvery { netWorthDao.updateAsset(any()) } returns Unit

        viewModel.addOrMergeAsset(addition)
        dispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { netWorthDao.insertAsset(any()) }
        coVerify(exactly = 1) {
            netWorthDao.updateAsset(match { it.id == 9L && it.quantity == 15.0 && it.currentValue == 1500.0 })
        }
    }
}
