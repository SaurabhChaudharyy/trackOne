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

    fun updateAsset(asset: NetWorthAssetEntity) = viewModelScope.launch {
        netWorthDao.updateAsset(asset)
    }

    fun deleteAsset(asset: NetWorthAssetEntity) = viewModelScope.launch {
        netWorthDao.deleteAsset(asset)
    }

    val assetSummary: LiveData<Map<AssetType, Double>> = allAssets.map { assets ->
        assets.groupBy { it.assetType }
            .mapValues { (_, list) -> list.sumOf { it.currentValue } }
    }

    suspend fun fetchLivePrice(symbol: String, assetType: AssetType): Resource<Double> =
        netWorthRepository.fetchLivePrice(symbol, assetType)
}
