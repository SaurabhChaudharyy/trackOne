package app.trackone.ui.networth

import androidx.lifecycle.*
import app.trackone.data.database.AssetType
import app.trackone.data.database.NetWorthAssetEntity
import app.trackone.data.database.NetWorthDao
import app.trackone.data.repository.NetWorthRepository
import app.trackone.data.repository.SymbolResolution
import app.trackone.data.repository.SymbolResolver
import app.trackone.utils.PortfolioGainLoss
import app.trackone.utils.Resource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NetWorthViewModel @Inject constructor(
    private val netWorthDao: NetWorthDao,
    private val netWorthRepository: NetWorthRepository,
    private val symbolResolver: SymbolResolver
) : ViewModel() {

    val allAssets: LiveData<List<NetWorthAssetEntity>> = netWorthDao.getAllAssets()
    val totalNetWorth: LiveData<Double?> = netWorthDao.getTotalNetWorth()

    private val _isRefreshing = MutableLiveData(false)
    val isRefreshing: LiveData<Boolean> = _isRefreshing

    /**
     * Pull-to-refresh: re-fetches live prices for every asset. [allAssets]/[totalNetWorth]
     * update on their own once Room's write lands — this just needs to trigger the fetch
     * and flip the spinner off when it's done. Always forced, since the user explicitly
     * asked for it.
     */
    fun refresh() = viewModelScope.launch {
        _isRefreshing.value = true
        netWorthRepository.refreshNetWorthAssets(force = true)
        _isRefreshing.value = false
    }

    fun addAsset(asset: NetWorthAssetEntity) = viewModelScope.launch {
        netWorthDao.insertAsset(asset)
    }

    /**
     * Smart insert:
     * - If [asset] has a buyPrice → always insert as a new entry (separate lot).
     * - If [asset] has NO buyPrice → look for an existing record with same name+type
     *   that also has no buyPrice. If found, merge (add quantity + currentValue).
     *   If not found, insert as new.
     */
    fun addOrMergeAsset(asset: NetWorthAssetEntity) = viewModelScope.launch {
        if (asset.buyPrice > 0.0) {
            // Has a buy price — always a distinct lot, insert fresh
            netWorthDao.insertAsset(asset)
            return@launch
        }
        val existing = netWorthDao.findMergeCandidate(asset.name, asset.assetType)
        if (existing != null) {
            // Merge: combine quantity and current value
            netWorthDao.updateAsset(
                existing.copy(
                    quantity     = existing.quantity + asset.quantity,
                    currentValue = existing.currentValue + asset.currentValue,
                    updatedAt    = System.currentTimeMillis()
                )
            )
        } else {
            netWorthDao.insertAsset(asset)
        }
    }

    fun updateAsset(asset: NetWorthAssetEntity) = viewModelScope.launch {
        netWorthDao.updateAsset(asset)
    }

    fun deleteAsset(asset: NetWorthAssetEntity) = viewModelScope.launch {
        netWorthDao.deleteAsset(asset)
    }

    fun deleteAssets(ids: Set<Long>) = viewModelScope.launch {
        netWorthDao.deleteAssetsByIds(ids.toList())
    }

    val assetSummary: LiveData<Map<AssetType, Double>> = allAssets.map { assets ->
        assets.groupBy { it.assetType }
            .mapValues { (_, list) -> list.sumOf { it.currentValue } }
    }

    /** Pair<absolutePnL, percentPnL>. See [PortfolioGainLoss] for the underlying formula. */
    val totalPnL: LiveData<Pair<Double, Double>> = allAssets.map { assets ->
        val gainLoss = PortfolioGainLoss.compute(assets)
        Pair(gainLoss.absChange, gainLoss.pctChange)
    }

    /** What ticker this holding most likely is (by its ISIN, then its name) — used to suggest a fix. */
    suspend fun resolveSymbol(asset: NetWorthAssetEntity): SymbolResolution =
        symbolResolver.resolve(asset.name, asset.isin, asset.assetType)

    suspend fun fetchLivePrice(symbol: String, assetType: AssetType): Resource<Double> =
        netWorthRepository.fetchLivePrice(symbol, assetType)
}
