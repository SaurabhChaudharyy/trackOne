package com.saurabh.financewidget.ui.networth

import androidx.lifecycle.*
import com.saurabh.financewidget.data.database.AssetType
import com.saurabh.financewidget.data.database.NetWorthAssetEntity
import com.saurabh.financewidget.data.database.NetWorthDao
import com.saurabh.financewidget.data.repository.NetWorthRepository
import com.saurabh.financewidget.utils.Resource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NetWorthViewModel @Inject constructor(
    private val netWorthDao: NetWorthDao,
    private val netWorthRepository: NetWorthRepository
) : ViewModel() {

    val allAssets: LiveData<List<NetWorthAssetEntity>> = netWorthDao.getAllAssets()
    val totalNetWorth: LiveData<Double?> = netWorthDao.getTotalNetWorth()

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

    /** Pair<absolutePnL, percentPnL>.
     *  For assets where buyPrice > 0: pnl = currentValue - buyPrice*quantity
     *  For assets where buyPrice == 0: invested = currentValue, pnl = 0
     */
    val totalPnL: LiveData<Pair<Double, Double>> = allAssets.map { assets ->
        var totalInvested = 0.0
        var totalCurrent  = 0.0
        for (asset in assets) {
            if (asset.buyPrice > 0.0) {
                val cost = asset.buyPrice * asset.quantity
                totalInvested += cost
                totalCurrent  += asset.currentValue
            } else {
                // No buy price known — treat as break-even
                totalInvested += asset.currentValue
                totalCurrent  += asset.currentValue
            }
        }
        val absChange = totalCurrent - totalInvested
        val pct = if (totalInvested > 0.0) (absChange / totalInvested) * 100.0 else 0.0
        Pair(absChange, pct)
    }

    suspend fun fetchLivePrice(symbol: String, assetType: AssetType): Resource<Double> =
        netWorthRepository.fetchLivePrice(symbol, assetType)
}
