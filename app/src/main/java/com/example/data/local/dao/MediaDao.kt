package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.entity.CategoryEntity
import com.example.data.local.entity.MediaItemEntity

data class ExistingTimestampTuple(
    val id: String,
    val addedTimestamp: Long,
    val hasServerAddedDate: Boolean
)

@Dao
interface MediaDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<MediaItemEntity>)

    @Query("SELECT id, addedTimestamp, hasServerAddedDate FROM media_items WHERE accountId = :accountId AND type = :type")
    suspend fun getExistingTimestamps(accountId: String, type: String): List<ExistingTimestampTuple>

    @Query("DELETE FROM media_items WHERE accountId = :accountId AND type = :type")
    suspend fun deleteByAccountAndType(accountId: String, type: String)

    @Query("DELETE FROM media_items WHERE accountId = :accountId")
    suspend fun deleteAllByAccount(accountId: String)

    @Query("DELETE FROM media_items")
    suspend fun clearAll()

    // Bounded queries for Home screen (no memory bloat)
    @Query("SELECT * FROM media_items WHERE accountId = :accountId AND type = :type ORDER BY rating DESC, addedTimestamp DESC LIMIT :limit")
    suspend fun getTopRated(accountId: String, type: String, limit: Int = 20): List<MediaItemEntity>

    @Query("SELECT * FROM media_items WHERE accountId = :accountId AND type IN ('MOVIE', 'SERIES') ORDER BY hasServerAddedDate DESC, addedTimestamp DESC, streamId DESC LIMIT :limit")
    suspend fun getRecentlyAdded(accountId: String, limit: Int = 20): List<MediaItemEntity>

    @Query("""
        SELECT * FROM media_items 
        WHERE accountId = :accountId 
          AND type = :type 
          AND (
              :categoryId = 'all' 
              OR :categoryId = 'vod_all' 
              OR :categoryId = 'series_all' 
              OR categoryId = :categoryId 
              OR categoryName LIKE '%' || :categoryId || '%'
          )
        ORDER BY hasServerAddedDate DESC, addedTimestamp DESC, streamId DESC, name ASC 
        LIMIT :limit
    """)
    suspend fun getLatestByCategory(
        accountId: String,
        type: String,
        categoryId: String,
        limit: Int = 40
    ): List<MediaItemEntity>

    @Query("SELECT * FROM media_items WHERE accountId = :accountId AND type = :type AND (categoryId = :categoryId OR :categoryId = 'all') ORDER BY rating DESC, addedTimestamp DESC LIMIT :limit")
    suspend fun getByCategory(accountId: String, type: String, categoryId: String, limit: Int = 15): List<MediaItemEntity>

    // --- Quality Update (when real video resolution is detected from VOD/Series info or ExoPlayer) ---
    @Query("UPDATE media_items SET quality = :quality, resolution = :quality WHERE id = :id")
    suspend fun updateMediaQuality(id: String, quality: String)

    // --- Live TV Paged Queries (Sorted in Room SQLite via Indexes) ---

    @Query("""
        SELECT * FROM media_items 
        WHERE accountId = :accountId 
          AND type = 'LIVE' 
          AND (:categoryId = 'all' OR categoryId = :categoryId)
          AND (:query = '' OR name LIKE '%' || :query || '%' OR categoryName LIKE '%' || :query || '%')
          AND (
              :qualityFilter = 'All'
              OR (:qualityFilter = '8K' AND (quality = '8K' OR quality LIKE '8K |%'))
              OR ((:qualityFilter = '4K' OR :qualityFilter = '4K UHD') AND (quality = '4K UHD' OR quality LIKE '4K UHD |%' OR quality = '4K' OR quality LIKE '4K |%'))
              OR (:qualityFilter = 'FHD' AND (quality = 'FHD' OR quality LIKE 'FHD |%'))
              OR (:qualityFilter = 'HD' AND (quality = 'HD' OR quality LIKE 'HD |%'))
              OR (:qualityFilter = 'SD' AND (quality = 'SD' OR quality LIKE 'SD |%'))
          )
        ORDER BY 
          CASE
              WHEN :sortByQuality = 0 THEN 0
              WHEN quality = '8K' OR quality LIKE '8K |%' THEN 5
              WHEN quality = '4K UHD' OR quality LIKE '4K UHD |%' OR quality = '4K' OR quality LIKE '4K |%' THEN 4
              WHEN quality = 'FHD' OR quality LIKE 'FHD |%' THEN 3
              WHEN quality = 'HD' OR quality LIKE 'HD |%' THEN 2
              WHEN quality = 'SD' OR quality LIKE 'SD |%' THEN 1
              ELSE 0
          END DESC,
          groupOrder ASC, seq ASC, name ASC 
        LIMIT :limit OFFSET :offset
    """)
    suspend fun queryLiveSmartPaged(
        accountId: String,
        categoryId: String,
        query: String,
        limit: Int,
        offset: Int,
        qualityFilter: String = "All",
        sortByQuality: Boolean = false
    ): List<MediaItemEntity>

    @Query("""
        SELECT * FROM media_items 
        WHERE accountId = :accountId 
          AND type = 'LIVE' 
          AND (:categoryId = 'all' OR categoryId = :categoryId)
          AND (:query = '' OR name LIKE '%' || :query || '%' OR categoryName LIKE '%' || :query || '%')
          AND (
              :qualityFilter = 'All'
              OR (:qualityFilter = '8K' AND (quality = '8K' OR quality LIKE '8K |%'))
              OR ((:qualityFilter = '4K' OR :qualityFilter = '4K UHD') AND (quality = '4K UHD' OR quality LIKE '4K UHD |%' OR quality = '4K' OR quality LIKE '4K |%'))
              OR (:qualityFilter = 'FHD' AND (quality = 'FHD' OR quality LIKE 'FHD |%'))
              OR (:qualityFilter = 'HD' AND (quality = 'HD' OR quality LIKE 'HD |%'))
              OR (:qualityFilter = 'SD' AND (quality = 'SD' OR quality LIKE 'SD |%'))
          )
        ORDER BY 
          CASE
              WHEN :sortByQuality = 0 THEN 0
              WHEN quality = '8K' OR quality LIKE '8K |%' THEN 5
              WHEN quality = '4K UHD' OR quality LIKE '4K UHD |%' OR quality = '4K' OR quality LIKE '4K |%' THEN 4
              WHEN quality = 'FHD' OR quality LIKE 'FHD |%' THEN 3
              WHEN quality = 'HD' OR quality LIKE 'HD |%' THEN 2
              WHEN quality = 'SD' OR quality LIKE 'SD |%' THEN 1
              ELSE 0
          END DESC,
          serverOrder ASC, name ASC 
        LIMIT :limit OFFSET :offset
    """)
    suspend fun queryLiveServerPaged(
        accountId: String,
        categoryId: String,
        query: String,
        limit: Int,
        offset: Int,
        qualityFilter: String = "All",
        sortByQuality: Boolean = false
    ): List<MediaItemEntity>

    @Query("""
        SELECT * FROM media_items 
        WHERE accountId = :accountId 
          AND type = 'LIVE' 
          AND (:categoryId = 'all' OR categoryId = :categoryId)
          AND (:query = '' OR name LIKE '%' || :query || '%' OR categoryName LIKE '%' || :query || '%')
          AND (
              :qualityFilter = 'All'
              OR (:qualityFilter = '8K' AND (quality = '8K' OR quality LIKE '8K |%'))
              OR ((:qualityFilter = '4K' OR :qualityFilter = '4K UHD') AND (quality = '4K UHD' OR quality LIKE '4K UHD |%' OR quality = '4K' OR quality LIKE '4K |%'))
              OR (:qualityFilter = 'FHD' AND (quality = 'FHD' OR quality LIKE 'FHD |%'))
              OR (:qualityFilter = 'HD' AND (quality = 'HD' OR quality LIKE 'HD |%'))
              OR (:qualityFilter = 'SD' AND (quality = 'SD' OR quality LIKE 'SD |%'))
          )
        ORDER BY 
          CASE
              WHEN :sortByQuality = 0 THEN 0
              WHEN quality = '8K' OR quality LIKE '8K |%' THEN 5
              WHEN quality = '4K UHD' OR quality LIKE '4K UHD |%' OR quality = '4K' OR quality LIKE '4K |%' THEN 4
              WHEN quality = 'FHD' OR quality LIKE 'FHD |%' THEN 3
              WHEN quality = 'HD' OR quality LIKE 'HD |%' THEN 2
              WHEN quality = 'SD' OR quality LIKE 'SD |%' THEN 1
              ELSE 0
          END DESC,
          groupKey ASC, seq ASC, name ASC 
        LIMIT :limit OFFSET :offset
    """)
    suspend fun queryLiveAZPaged(
        accountId: String,
        categoryId: String,
        query: String,
        limit: Int,
        offset: Int,
        qualityFilter: String = "All",
        sortByQuality: Boolean = false
    ): List<MediaItemEntity>

    // --- Live TV Favorites Queries (Sorted in Room SQLite) ---

    @Query("""
        SELECT * FROM media_items 
        WHERE accountId = :accountId 
          AND id IN (:ids)
          AND (:query = '' OR name LIKE '%' || :query || '%')
          AND (
              :qualityFilter = 'All'
              OR (:qualityFilter = '8K' AND (quality = '8K' OR quality LIKE '8K |%'))
              OR ((:qualityFilter = '4K' OR :qualityFilter = '4K UHD') AND (quality = '4K UHD' OR quality LIKE '4K UHD |%' OR quality = '4K' OR quality LIKE '4K |%'))
              OR (:qualityFilter = 'FHD' AND (quality = 'FHD' OR quality LIKE 'FHD |%'))
              OR (:qualityFilter = 'HD' AND (quality = 'HD' OR quality LIKE 'HD |%'))
              OR (:qualityFilter = 'SD' AND (quality = 'SD' OR quality LIKE 'SD |%'))
          )
        ORDER BY 
          CASE
              WHEN :sortByQuality = 0 THEN 0
              WHEN quality = '8K' OR quality LIKE '8K |%' THEN 5
              WHEN quality = '4K UHD' OR quality LIKE '4K UHD |%' OR quality = '4K' OR quality LIKE '4K |%' THEN 4
              WHEN quality = 'FHD' OR quality LIKE 'FHD |%' THEN 3
              WHEN quality = 'HD' OR quality LIKE 'HD |%' THEN 2
              WHEN quality = 'SD' OR quality LIKE 'SD |%' THEN 1
              ELSE 0
          END DESC,
          groupOrder ASC, seq ASC, name ASC
    """)
    suspend fun getFavoritesSmart(
        accountId: String,
        ids: List<String>,
        query: String = "",
        qualityFilter: String = "All",
        sortByQuality: Boolean = false
    ): List<MediaItemEntity>

    @Query("""
        SELECT * FROM media_items 
        WHERE accountId = :accountId 
          AND id IN (:ids)
          AND (:query = '' OR name LIKE '%' || :query || '%')
          AND (
              :qualityFilter = 'All'
              OR (:qualityFilter = '8K' AND (quality = '8K' OR quality LIKE '8K |%'))
              OR ((:qualityFilter = '4K' OR :qualityFilter = '4K UHD') AND (quality = '4K UHD' OR quality LIKE '4K UHD |%' OR quality = '4K' OR quality LIKE '4K |%'))
              OR (:qualityFilter = 'FHD' AND (quality = 'FHD' OR quality LIKE 'FHD |%'))
              OR (:qualityFilter = 'HD' AND (quality = 'HD' OR quality LIKE 'HD |%'))
              OR (:qualityFilter = 'SD' AND (quality = 'SD' OR quality LIKE 'SD |%'))
          )
        ORDER BY 
          CASE
              WHEN :sortByQuality = 0 THEN 0
              WHEN quality = '8K' OR quality LIKE '8K |%' THEN 5
              WHEN quality = '4K UHD' OR quality LIKE '4K UHD |%' OR quality = '4K' OR quality LIKE '4K |%' THEN 4
              WHEN quality = 'FHD' OR quality LIKE 'FHD |%' THEN 3
              WHEN quality = 'HD' OR quality LIKE 'HD |%' THEN 2
              WHEN quality = 'SD' OR quality LIKE 'SD |%' THEN 1
              ELSE 0
          END DESC,
          serverOrder ASC, name ASC
    """)
    suspend fun getFavoritesServer(
        accountId: String,
        ids: List<String>,
        query: String = "",
        qualityFilter: String = "All",
        sortByQuality: Boolean = false
    ): List<MediaItemEntity>

    @Query("""
        SELECT * FROM media_items 
        WHERE accountId = :accountId 
          AND id IN (:ids)
          AND (:query = '' OR name LIKE '%' || :query || '%')
          AND (
              :qualityFilter = 'All'
              OR (:qualityFilter = '8K' AND (quality = '8K' OR quality LIKE '8K |%'))
              OR ((:qualityFilter = '4K' OR :qualityFilter = '4K UHD') AND (quality = '4K UHD' OR quality LIKE '4K UHD |%' OR quality = '4K' OR quality LIKE '4K |%'))
              OR (:qualityFilter = 'FHD' AND (quality = 'FHD' OR quality LIKE 'FHD |%'))
              OR (:qualityFilter = 'HD' AND (quality = 'HD' OR quality LIKE 'HD |%'))
              OR (:qualityFilter = 'SD' AND (quality = 'SD' OR quality LIKE 'SD |%'))
          )
        ORDER BY 
          CASE
              WHEN :sortByQuality = 0 THEN 0
              WHEN quality = '8K' OR quality LIKE '8K |%' THEN 5
              WHEN quality = '4K UHD' OR quality LIKE '4K UHD |%' OR quality = '4K' OR quality LIKE '4K |%' THEN 4
              WHEN quality = 'FHD' OR quality LIKE 'FHD |%' THEN 3
              WHEN quality = 'HD' OR quality LIKE 'HD |%' THEN 2
              WHEN quality = 'SD' OR quality LIKE 'SD |%' THEN 1
              ELSE 0
          END DESC,
          groupKey ASC, seq ASC, name ASC
    """)
    suspend fun getFavoritesAZ(
        accountId: String,
        ids: List<String>,
        query: String = "",
        qualityFilter: String = "All",
        sortByQuality: Boolean = false
    ): List<MediaItemEntity>

    // --- Paged & Filtered Queries for Movies & Series ---
    @Query("""
        SELECT * FROM media_items 
        WHERE accountId = :accountId 
          AND type = :type 
          AND (:categoryId = 'all' OR categoryId = :categoryId)
          AND (:query = '' OR name LIKE '%' || :query || '%')
          AND rating >= :minRating
          AND (:year = 'All' OR year = :year)
          AND (
              :qualityFilter = 'All'
              OR (:qualityFilter = '8K' AND (quality = '8K' OR quality LIKE '8K |%'))
              OR ((:qualityFilter = '4K' OR :qualityFilter = '4K UHD') AND (quality = '4K UHD' OR quality LIKE '4K UHD |%' OR quality = '4K' OR quality LIKE '4K |%'))
              OR (:qualityFilter = 'FHD' AND (quality = 'FHD' OR quality LIKE 'FHD |%'))
              OR (:qualityFilter = 'HD' AND (quality = 'HD' OR quality LIKE 'HD |%'))
              OR (:qualityFilter = 'SD' AND (quality = 'SD' OR quality LIKE 'SD |%'))
          )
        ORDER BY 
          CASE
              WHEN :sortByQuality = 0 THEN 0
              WHEN quality = '8K' OR quality LIKE '8K |%' THEN 5
              WHEN quality = '4K UHD' OR quality LIKE '4K UHD |%' OR quality = '4K' OR quality LIKE '4K |%' THEN 4
              WHEN quality = 'FHD' OR quality LIKE 'FHD |%' THEN 3
              WHEN quality = 'HD' OR quality LIKE 'HD |%' THEN 2
              WHEN quality = 'SD' OR quality LIKE 'SD |%' THEN 1
              ELSE 0
          END DESC,
          rating DESC, addedTimestamp DESC, name ASC 
        LIMIT :limit OFFSET :offset
    """)
    suspend fun queryMediaPaged(
        accountId: String,
        type: String,
        categoryId: String,
        query: String,
        minRating: Double,
        year: String,
        limit: Int,
        offset: Int,
        qualityFilter: String = "All",
        sortByQuality: Boolean = false
    ): List<MediaItemEntity>

    @Query("""
        SELECT COUNT(*) FROM media_items 
        WHERE accountId = :accountId 
          AND type = :type 
          AND (:categoryId = 'all' OR categoryId = :categoryId)
          AND (:query = '' OR name LIKE '%' || :query || '%')
          AND rating >= :minRating
          AND (:year = 'All' OR year = :year)
          AND (
              :qualityFilter = 'All'
              OR (:qualityFilter = '8K' AND (quality = '8K' OR quality LIKE '8K |%'))
              OR ((:qualityFilter = '4K' OR :qualityFilter = '4K UHD') AND (quality = '4K UHD' OR quality LIKE '4K UHD |%' OR quality = '4K' OR quality LIKE '4K |%'))
              OR (:qualityFilter = 'FHD' AND (quality = 'FHD' OR quality LIKE 'FHD |%'))
              OR (:qualityFilter = 'HD' AND (quality = 'HD' OR quality LIKE 'HD |%'))
              OR (:qualityFilter = 'SD' AND (quality = 'SD' OR quality LIKE 'SD |%'))
          )
    """)
    suspend fun countMedia(
        accountId: String,
        type: String,
        categoryId: String,
        query: String,
        minRating: Double,
        year: String,
        qualityFilter: String = "All"
    ): Int

    @Query("SELECT * FROM media_items WHERE id = :id LIMIT 1")
    suspend fun getItemById(id: String): MediaItemEntity?

    @Query("SELECT * FROM media_items WHERE id IN (:ids) ORDER BY groupOrder ASC, seq ASC, name ASC")
    suspend fun getItemsByIds(ids: List<String>): List<MediaItemEntity>

    // --- Search Queries (Sorted in Room SQLite) ---

    @Query("""
        SELECT * FROM media_items 
        WHERE accountId = :accountId 
          AND (:query = '' OR name LIKE '%' || :query || '%' OR categoryName LIKE '%' || :query || '%')
        ORDER BY groupOrder ASC, seq ASC, name ASC 
        LIMIT 100
    """)
    suspend fun searchAll(accountId: String, query: String): List<MediaItemEntity>

    @Query("""
        SELECT * FROM media_items 
        WHERE accountId = :accountId 
          AND (:query = '' OR name LIKE '%' || :query || '%' OR categoryName LIKE '%' || :query || '%')
        ORDER BY serverOrder ASC, name ASC 
        LIMIT 100
    """)
    suspend fun searchAllServer(accountId: String, query: String): List<MediaItemEntity>

    @Query("""
        SELECT * FROM media_items 
        WHERE accountId = :accountId 
          AND (:query = '' OR name LIKE '%' || :query || '%' OR categoryName LIKE '%' || :query || '%')
        ORDER BY groupKey ASC, seq ASC, name ASC 
        LIMIT 100
    """)
    suspend fun searchAllAZ(accountId: String, query: String): List<MediaItemEntity>

    // Categories
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategories(categories: List<CategoryEntity>)

    @Query("DELETE FROM categories WHERE accountId = :accountId AND type = :type")
    suspend fun deleteCategoriesByAccountAndType(accountId: String, type: String)

    @Query("SELECT * FROM categories WHERE accountId = :accountId AND type = :type")
    suspend fun getCategories(accountId: String, type: String): List<CategoryEntity>

    @Query("DELETE FROM categories WHERE accountId = :accountId")
    suspend fun deleteCategoriesByAccount(accountId: String)
}
