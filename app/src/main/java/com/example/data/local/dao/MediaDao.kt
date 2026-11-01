package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.data.local.entity.CategoryEntity
import com.example.data.local.entity.MediaItemEntity

@Dao
interface MediaDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<MediaItemEntity>)

    @Query("DELETE FROM media_items WHERE accountId = :accountId AND type = :type")
    suspend fun deleteByAccountAndType(accountId: String, type: String)

    @Query("DELETE FROM media_items WHERE accountId = :accountId")
    suspend fun deleteAllByAccount(accountId: String)

    @Query("DELETE FROM media_items")
    suspend fun clearAll()

    // Bounded queries for Home screen (no memory bloat)
    @Query("SELECT * FROM media_items WHERE accountId = :accountId AND type = :type ORDER BY rating DESC, addedTimestamp DESC LIMIT :limit")
    suspend fun getTopRated(accountId: String, type: String, limit: Int = 20): List<MediaItemEntity>

    @Query("SELECT * FROM media_items WHERE accountId = :accountId AND type IN ('MOVIE', 'SERIES') ORDER BY year DESC, addedTimestamp DESC LIMIT :limit")
    suspend fun getRecentlyAdded(accountId: String, limit: Int = 20): List<MediaItemEntity>

    @Query("SELECT * FROM media_items WHERE accountId = :accountId AND type = :type AND (categoryId = :categoryId OR :categoryId = 'all') ORDER BY rating DESC, addedTimestamp DESC LIMIT :limit")
    suspend fun getByCategory(accountId: String, type: String, categoryId: String, limit: Int = 15): List<MediaItemEntity>

    // Paged & Filtered Queries for Movies, Series, Live TV
    @Query("""
        SELECT * FROM media_items 
        WHERE accountId = :accountId 
          AND type = :type 
          AND (:categoryId = 'all' OR categoryId = :categoryId)
          AND (:query = '' OR name LIKE '%' || :query || '%')
          AND rating >= :minRating
          AND (:year = 'All' OR year = :year)
        ORDER BY rating DESC, addedTimestamp DESC 
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
        offset: Int
    ): List<MediaItemEntity>

    @Query("""
        SELECT COUNT(*) FROM media_items 
        WHERE accountId = :accountId 
          AND type = :type 
          AND (:categoryId = 'all' OR categoryId = :categoryId)
          AND (:query = '' OR name LIKE '%' || :query || '%')
          AND rating >= :minRating
          AND (:year = 'All' OR year = :year)
    """)
    suspend fun countMedia(
        accountId: String,
        type: String,
        categoryId: String,
        query: String,
        minRating: Double,
        year: String
    ): Int

    @Query("SELECT * FROM media_items WHERE id = :id LIMIT 1")
    suspend fun getItemById(id: String): MediaItemEntity?

    @Query("SELECT * FROM media_items WHERE id IN (:ids)")
    suspend fun getItemsByIds(ids: List<String>): List<MediaItemEntity>

    @Query("SELECT * FROM media_items WHERE accountId = :accountId AND (:query = '' OR name LIKE '%' || :query || '%') LIMIT 50")
    suspend fun searchAll(accountId: String, query: String): List<MediaItemEntity>

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
