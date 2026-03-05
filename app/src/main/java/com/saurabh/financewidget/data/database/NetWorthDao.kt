package com.saurabh.financewidget.data.database

import androidx.lifecycle.LiveData
import androidx.room.*

@Dao
interface NetWorthDao {

    @Query("SELECT * FROM networth_assets ORDER BY assetType ASC, name ASC")
    fun getAllAssets(): LiveData<List<NetWorthAssetEntity>>

    @Query("SELECT * FROM networth_assets ORDER BY assetType ASC, name ASC")
    suspend fun getAllAssetsSync(): List<NetWorthAssetEntity>

    @Query("SELECT * FROM networth_assets WHERE assetType = :type ORDER BY name ASC")
    fun getAssetsByType(type: AssetType): LiveData<List<NetWorthAssetEntity>>

    @Query("SELECT SUM(currentValue) FROM networth_assets")
    fun getTotalNetWorth(): LiveData<Double?>

    @Query("SELECT SUM(currentValue) FROM networth_assets WHERE assetType = :type")
    fun getTotalByType(type: AssetType): LiveData<Double?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAsset(asset: NetWorthAssetEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAssets(assets: List<NetWorthAssetEntity>)

    @Query("DELETE FROM networth_assets")
    suspend fun deleteAllAssets()

    @Update
    suspend fun updateAsset(asset: NetWorthAssetEntity)

    @Delete
    suspend fun deleteAsset(asset: NetWorthAssetEntity)

    @Query("DELETE FROM networth_assets WHERE id = :id")
    suspend fun deleteAssetById(id: Long)

    @Query("SELECT * FROM networth_assets WHERE id = :id")
    suspend fun getAssetById(id: Long): NetWorthAssetEntity?
}
