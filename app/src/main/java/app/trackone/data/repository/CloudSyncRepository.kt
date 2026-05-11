package app.trackone.data.repository

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import app.trackone.data.database.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Summary returned after a backup operation. */
data class BackupStats(val watchlistGroups: Int, val watchlistItems: Int, val assets: Int)

/** Summary returned after a restore operation. */
data class RestoreStats(val watchlistGroups: Int, val watchlistItems: Int, val assets: Int)

/**
 * Reads data from Room and writes it to Cloud Firestore under the signed-in user's
 * document tree, and vice-versa.
 *
 * Firestore document structure:
 * ```
 * users/{uid}/
 *   metadata           → { lastSync, appVersion }
 *   watchlist_groups/   → one doc per group
 *   watchlist/          → one doc per (symbol_groupId) entry
 *   networth_assets/    → one doc per asset id
 * ```
 */
@Singleton
class CloudSyncRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth,
    private val watchlistDao: WatchlistDao,
    private val watchlistGroupDao: WatchlistGroupDao,
    private val netWorthDao: NetWorthDao
) {
    companion object {
        private const val TAG = "CloudSync"
        private const val COL_USERS = "users"
        private const val COL_WATCHLIST_GROUPS = "watchlist_groups"
        private const val COL_WATCHLIST = "watchlist"
        private const val COL_NETWORTH = "networth_assets"
    }

    // ──────────────────────────────────────────────────────────────────────
    //  B A C K U P   (Room → Firestore)
    // ──────────────────────────────────────────────────────────────────────

    suspend fun backupToCloud(): Result<BackupStats> = withContext(Dispatchers.IO) {
        val uid = auth.currentUser?.uid
            ?: return@withContext Result.failure(Exception("Not signed in"))
        try {
            val userDoc = firestore.collection(COL_USERS).document(uid)

            // ── 1. Watchlist groups ──
            val groups = watchlistGroupDao.getAllGroupsSync()
            val groupsCol = userDoc.collection(COL_WATCHLIST_GROUPS)
            // Clear existing cloud data so deleted items don't persist
            deleteCollection(groupsCol)
            for (group in groups) {
                groupsCol.document(group.id.toString()).set(
                    mapOf(
                        "name" to group.name,
                        "position" to group.position,
                        "createdAt" to group.createdAt
                    )
                ).await()
            }

            // ── 2. Watchlist items ──
            val items = watchlistDao.getWatchlistSync()
            val watchlistCol = userDoc.collection(COL_WATCHLIST)
            deleteCollection(watchlistCol)
            for (item in items) {
                val docId = "${item.symbol}_${item.groupId}"
                watchlistCol.document(docId).set(
                    mapOf(
                        "symbol" to item.symbol,
                        "displayName" to item.displayName,
                        "position" to item.position,
                        "groupId" to item.groupId,
                        "addedAt" to item.addedAt
                    )
                ).await()
            }

            // ── 3. Net worth assets ──
            val assets = netWorthDao.getAllAssetsSync()
            val assetsCol = userDoc.collection(COL_NETWORTH)
            deleteCollection(assetsCol)
            for (asset in assets) {
                assetsCol.document(asset.id.toString()).set(
                    mapOf(
                        "name" to asset.name,
                        "assetType" to asset.assetType.name,
                        "quantity" to asset.quantity,
                        "buyPrice" to asset.buyPrice,
                        "currentValue" to asset.currentValue,
                        "currency" to asset.currency,
                        "notes" to asset.notes,
                        "addedAt" to asset.addedAt,
                        "updatedAt" to asset.updatedAt
                    )
                ).await()
            }

            // ── 4. Metadata ──
            userDoc.set(
                mapOf(
                    "lastSync" to System.currentTimeMillis(),
                    "appVersion" to "1.2"
                )
            ).await()

            val stats = BackupStats(groups.size, items.size, assets.size)
            Log.d(TAG, "backupToCloud: success – $stats")
            Result.success(stats)
        } catch (e: Exception) {
            Log.e(TAG, "backupToCloud: failed", e)
            Result.failure(e)
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    //  R E S T O R E   (Firestore → Room)
    // ──────────────────────────────────────────────────────────────────────

    suspend fun restoreFromCloud(): Result<RestoreStats> = withContext(Dispatchers.IO) {
        val uid = auth.currentUser?.uid
            ?: return@withContext Result.failure(Exception("Not signed in"))
        try {
            val userDoc = firestore.collection(COL_USERS).document(uid)

            // ── 1. Watchlist groups ──
            val groupsDocs = userDoc.collection(COL_WATCHLIST_GROUPS).get().await()
            val groups = groupsDocs.documents.mapNotNull { doc ->
                try {
                    WatchlistGroupEntity(
                        id = doc.id.toLong(),
                        name = doc.getString("name") ?: return@mapNotNull null,
                        position = (doc.getLong("position") ?: 0).toInt(),
                        createdAt = doc.getLong("createdAt") ?: System.currentTimeMillis()
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Skipping invalid group doc: ${doc.id}", e)
                    null
                }
            }

            // ── 2. Watchlist items ──
            val watchlistDocs = userDoc.collection(COL_WATCHLIST).get().await()
            val watchlistItems = watchlistDocs.documents.mapNotNull { doc ->
                try {
                    WatchlistEntity(
                        symbol = doc.getString("symbol") ?: return@mapNotNull null,
                        displayName = doc.getString("displayName") ?: "",
                        position = (doc.getLong("position") ?: 0).toInt(),
                        groupId = doc.getLong("groupId") ?: 1L,
                        addedAt = doc.getLong("addedAt") ?: System.currentTimeMillis()
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Skipping invalid watchlist doc: ${doc.id}", e)
                    null
                }
            }

            // ── 3. Net worth assets ──
            val assetsDocs = userDoc.collection(COL_NETWORTH).get().await()
            val assets = assetsDocs.documents.mapNotNull { doc ->
                try {
                    val typeStr = doc.getString("assetType") ?: return@mapNotNull null
                    val assetType = try { AssetType.valueOf(typeStr) } catch (_: Exception) { return@mapNotNull null }
                    NetWorthAssetEntity(
                        id = 0,   // Auto-generate new IDs on restore
                        name = doc.getString("name") ?: return@mapNotNull null,
                        assetType = assetType,
                        quantity = doc.getDouble("quantity") ?: 1.0,
                        buyPrice = doc.getDouble("buyPrice") ?: 0.0,
                        currentValue = doc.getDouble("currentValue") ?: 0.0,
                        currency = doc.getString("currency") ?: "INR",
                        notes = doc.getString("notes") ?: "",
                        addedAt = doc.getLong("addedAt") ?: System.currentTimeMillis(),
                        updatedAt = doc.getLong("updatedAt") ?: System.currentTimeMillis()
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Skipping invalid asset doc: ${doc.id}", e)
                    null
                }
            }

            // ── 4. Write to Room (replace all local data) ──
            // Clear local tables
            watchlistDao.clearWatchlist()
            // We can't easily "deleteAll" groups via current DAO, so delete them one-by-one
            val existingGroups = watchlistGroupDao.getAllGroupsSync()
            for (g in existingGroups) {
                watchlistGroupDao.deleteGroup(g.id)
            }
            netWorthDao.deleteAllAssets()

            // Insert restored data
            for (group in groups) {
                watchlistGroupDao.insertGroup(group)
            }
            if (watchlistItems.isNotEmpty()) {
                watchlistDao.insertWatchlistItems(watchlistItems)
            }
            if (assets.isNotEmpty()) {
                netWorthDao.insertAssets(assets)
            }

            val stats = RestoreStats(groups.size, watchlistItems.size, assets.size)
            Log.d(TAG, "restoreFromCloud: success – $stats")
            Result.success(stats)
        } catch (e: Exception) {
            Log.e(TAG, "restoreFromCloud: failed", e)
            Result.failure(e)
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    //  L A S T   S Y N C   T I M E
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Reads the `lastSync` timestamp from the user's Firestore metadata document.
     * Returns `null` if no backup has ever been made.
     */
    suspend fun getLastSyncTime(): Long? = withContext(Dispatchers.IO) {
        val uid = auth.currentUser?.uid ?: return@withContext null
        try {
            val doc = firestore.collection(COL_USERS).document(uid).get().await()
            doc.getLong("lastSync")
        } catch (e: Exception) {
            Log.w(TAG, "getLastSyncTime: failed", e)
            null
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    //  H E L P E R S
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Deletes all documents in a Firestore collection.
     * Firestore doesn't support collection-level deletes so we fetch + delete each doc.
     */
    private suspend fun deleteCollection(
        collection: com.google.firebase.firestore.CollectionReference
    ) {
        val snapshot = collection.get().await()
        for (doc in snapshot.documents) {
            doc.reference.delete().await()
        }
    }
}
