package com.example.data.local

import android.content.Context
import android.content.SharedPreferences
import com.example.data.local.db.KrasDatabase
import com.example.data.local.entity.CategoryEntity
import com.example.data.local.entity.MediaItemEntity
import com.example.data.model.*
import com.example.data.sample.SampleData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class KrasRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("kras_tv_prefs", Context.MODE_PRIVATE)

    private val db = KrasDatabase.getInstance(context)
    val mediaDao = db.mediaDao()

    // --- Accounts ---
    fun getAccounts(): List<Account> {
        val jsonStr = prefs.getString("saved_accounts", null) ?: return listOf(SampleData.demoAccount)
        val list = mutableListOf<Account>()
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    Account(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        serverUrl = obj.getString("serverUrl"),
                        username = obj.getString("username"),
                        password = obj.getString("password"),
                        isActive = obj.optBoolean("isActive", false),
                        status = obj.optString("status", "Active"),
                        expDate = obj.optString("expDate", ""),
                        maxConnections = obj.optString("maxConnections", "1"),
                        isDemo = obj.optBoolean("isDemo", false),
                        createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        if (list.isEmpty()) {
            list.add(SampleData.demoAccount)
        }
        return list
    }

    fun saveAccounts(accounts: List<Account>) {
        try {
            val array = JSONArray()
            for (acc in accounts) {
                val obj = JSONObject().apply {
                    put("id", acc.id)
                    put("name", acc.name)
                    put("serverUrl", acc.serverUrl)
                    put("username", acc.username)
                    put("password", acc.password)
                    put("isActive", acc.isActive)
                    put("status", acc.status)
                    put("expDate", acc.expDate)
                    put("maxConnections", acc.maxConnections)
                    put("isDemo", acc.isDemo)
                    put("createdAt", acc.createdAt)
                }
                array.put(obj)
            }
            prefs.edit().putString("saved_accounts", array.toString()).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getActiveAccount(): Account {
        val accounts = getAccounts()
        return accounts.firstOrNull { it.isActive } ?: accounts.first()
    }

    fun setActiveAccount(accountId: String) {
        val accounts = getAccounts().map {
            it.copy(isActive = (it.id == accountId))
        }
        saveAccounts(accounts)
    }

    fun addAccount(account: Account) {
        val existing = getAccounts().filter { it.id != account.id }
        val updated = existing.map { it.copy(isActive = false) } + account.copy(isActive = true)
        saveAccounts(updated)
    }

    fun removeAccount(accountId: String) {
        val remaining = getAccounts().filter { it.id != accountId }
        val finalAccounts = if (remaining.none { it.isActive } && remaining.isNotEmpty()) {
            remaining.mapIndexed { idx, acc -> if (idx == 0) acc.copy(isActive = true) else acc }
        } else remaining
        saveAccounts(if (finalAccounts.isEmpty()) listOf(SampleData.demoAccount) else finalAccounts)
    }

    // --- Favorites ---
    fun getFavorites(): List<FavoriteItem> {
        val jsonStr = prefs.getString("favorites_list", null) ?: return emptyList()
        val list = mutableListOf<FavoriteItem>()
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    FavoriteItem(
                        id = obj.getString("id"),
                        mediaId = obj.getString("mediaId"),
                        type = MediaType.valueOf(obj.getString("type")),
                        title = obj.getString("title"),
                        posterUrl = obj.optString("posterUrl", ""),
                        rating = obj.optDouble("rating", 0.0),
                        year = obj.optString("year", ""),
                        categoryName = obj.optString("categoryName", ""),
                        streamUrl = obj.optString("streamUrl", ""),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun toggleFavorite(item: MediaItem): Boolean {
        val list = getFavorites().toMutableList()
        val existing = list.find { it.mediaId == item.id }
        val isAdded: Boolean
        if (existing != null) {
            list.remove(existing)
            isAdded = false
        } else {
            list.add(
                FavoriteItem(
                    id = java.util.UUID.randomUUID().toString(),
                    mediaId = item.id,
                    type = item.type,
                    title = item.name,
                    posterUrl = item.streamIcon,
                    rating = item.rating,
                    year = item.year,
                    categoryName = item.categoryName,
                    streamUrl = item.streamUrl
                )
            )
            isAdded = true
        }
        try {
            val array = JSONArray()
            for (fav in list) {
                val obj = JSONObject().apply {
                    put("id", fav.id)
                    put("mediaId", fav.mediaId)
                    put("type", fav.type.name)
                    put("title", fav.title)
                    put("posterUrl", fav.posterUrl)
                    put("rating", fav.rating)
                    put("year", fav.year)
                    put("categoryName", fav.categoryName)
                    put("streamUrl", fav.streamUrl)
                    put("timestamp", fav.timestamp)
                }
                array.put(obj)
            }
            prefs.edit().putString("favorites_list", array.toString()).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return isAdded
    }

    fun isFavorite(mediaId: String): Boolean {
        return getFavorites().any { it.mediaId == mediaId }
    }

    // --- Watch History ---
    fun getWatchHistory(): List<WatchProgress> {
        val jsonStr = prefs.getString("watch_history", null) ?: return emptyList()
        val list = mutableListOf<WatchProgress>()
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    WatchProgress(
                        id = obj.getString("id"),
                        mediaId = obj.getString("mediaId"),
                        title = obj.getString("title"),
                        type = MediaType.valueOf(obj.getString("type")),
                        posterUrl = obj.optString("posterUrl", ""),
                        positionMs = obj.getLong("positionMs"),
                        durationMs = obj.getLong("durationMs"),
                        seasonNum = if (obj.has("seasonNum")) obj.getInt("seasonNum") else null,
                        episodeNum = if (obj.has("episodeNum")) obj.getInt("episodeNum") else null,
                        streamUrl = obj.optString("streamUrl", ""),
                        updatedAt = obj.optLong("updatedAt", System.currentTimeMillis())
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list.sortedByDescending { it.updatedAt }
    }

    fun saveWatchProgress(progress: WatchProgress) {
        val list = getWatchHistory().filter { it.mediaId != progress.mediaId }.toMutableList()
        list.add(0, progress)
        val trimmed = list.take(50)
        try {
            val array = JSONArray()
            for (wp in trimmed) {
                val obj = JSONObject().apply {
                    put("id", wp.id)
                    put("mediaId", wp.mediaId)
                    put("title", wp.title)
                    put("type", wp.type.name)
                    put("posterUrl", wp.posterUrl)
                    put("positionMs", wp.positionMs)
                    put("durationMs", wp.durationMs)
                    if (wp.seasonNum != null) put("seasonNum", wp.seasonNum)
                    if (wp.episodeNum != null) put("episodeNum", wp.episodeNum)
                    put("streamUrl", wp.streamUrl)
                    put("updatedAt", wp.updatedAt)
                }
                array.put(obj)
            }
            prefs.edit().putString("watch_history", array.toString()).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // --- Downloads ---
    fun getDownloads(): List<DownloadItem> {
        val jsonStr = prefs.getString("downloads_list", null) ?: return emptyList()
        val list = mutableListOf<DownloadItem>()
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    DownloadItem(
                        id = obj.getString("id"),
                        mediaId = obj.getString("mediaId"),
                        title = obj.getString("title"),
                        type = MediaType.valueOf(obj.getString("type")),
                        posterUrl = obj.optString("posterUrl", ""),
                        status = DownloadStatus.valueOf(obj.getString("status")),
                        progress = obj.getDouble("progress").toFloat(),
                        downloadedBytes = obj.getLong("downloadedBytes"),
                        fileSizeBytes = obj.getLong("fileSizeBytes"),
                        streamUrl = obj.optString("streamUrl", "")
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun saveDownloads(downloads: List<DownloadItem>) {
        try {
            val array = JSONArray()
            for (dl in downloads) {
                val obj = JSONObject().apply {
                    put("id", dl.id)
                    put("mediaId", dl.mediaId)
                    put("title", dl.title)
                    put("type", dl.type.name)
                    put("posterUrl", dl.posterUrl)
                    put("status", dl.status.name)
                    put("progress", dl.progress)
                    put("downloadedBytes", dl.downloadedBytes)
                    put("fileSizeBytes", dl.fileSizeBytes)
                    put("streamUrl", dl.streamUrl)
                }
                array.put(obj)
            }
            prefs.edit().putString("downloads_list", array.toString()).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // --- Settings ---
    fun getUserSettings(): UserSettings {
        return UserSettings(
            accentColorIndex = prefs.getInt("accentColorIndex", 0),
            blurIntensity = prefs.getString("blurIntensity", "Medium") ?: "Medium",
            cardStyle = CardStyle.valueOf(prefs.getString("cardStyle", CardStyle.POSTER.name) ?: CardStyle.POSTER.name),
            defaultQuality = prefs.getString("defaultQuality", "Auto") ?: "Auto",
            bufferSize = prefs.getString("bufferSize", "Large") ?: "Large",
            defaultAspectRatio = AspectRatioMode.valueOf(prefs.getString("defaultAspectRatio", AspectRatioMode.FIT.name) ?: AspectRatioMode.FIT.name),
            language = prefs.getString("language", "English") ?: "English",
            parentalPin = prefs.getString("parentalPin", "") ?: "",
            parentalEnabled = prefs.getBoolean("parentalEnabled", false),
            playIntroSound = prefs.getBoolean("playIntroSound", true),
            silenceNotificationsWhileWatching = prefs.getBoolean("silenceNotificationsWhileWatching", false)
        )
    }

    fun saveUserSettings(settings: UserSettings) {
        prefs.edit().apply {
            putInt("accentColorIndex", settings.accentColorIndex)
            putString("blurIntensity", settings.blurIntensity)
            putString("cardStyle", settings.cardStyle.name)
            putString("defaultQuality", settings.defaultQuality)
            putString("bufferSize", settings.bufferSize)
            putString("defaultAspectRatio", settings.defaultAspectRatio.name)
            putString("language", settings.language)
            putString("parentalPin", settings.parentalPin)
            putBoolean("parentalEnabled", settings.parentalEnabled)
            putBoolean("playIntroSound", settings.playIntroSound)
            putBoolean("silenceNotificationsWhileWatching", settings.silenceNotificationsWhileWatching)
        }.apply()
    }

    fun getSavedInterruptionFilter(): Int? {
        return if (prefs.contains("saved_dnd_filter")) prefs.getInt("saved_dnd_filter", -1) else null
    }

    fun saveInterruptionFilter(filter: Int?) {
        if (filter != null) {
            prefs.edit().putInt("saved_dnd_filter", filter).apply()
        } else {
            prefs.edit().remove("saved_dnd_filter").apply()
        }
    }

    // --- Room Database Batch Operations ---

    suspend fun insertMediaBatch(items: List<MediaItemEntity>) = withContext(Dispatchers.IO) {
        mediaDao.insertAll(items)
    }

    suspend fun clearOldRows(accountId: String, type: String) = withContext(Dispatchers.IO) {
        mediaDao.deleteByAccountAndType(accountId, type)
    }

    suspend fun clearAllMedia() = withContext(Dispatchers.IO) {
        mediaDao.clearAll()
    }

    suspend fun getTopRated(accountId: String, type: String, limit: Int = 20): List<MediaItem> = withContext(Dispatchers.IO) {
        mediaDao.getTopRated(accountId, type, limit).map { it.toMediaItem() }
    }

    suspend fun getRecentlyAdded(accountId: String, limit: Int = 20): List<MediaItem> = withContext(Dispatchers.IO) {
        mediaDao.getRecentlyAdded(accountId, limit).map { it.toMediaItem() }
    }

    suspend fun getByCategory(accountId: String, type: String, categoryId: String, limit: Int = 15): List<MediaItem> = withContext(Dispatchers.IO) {
        mediaDao.getByCategory(accountId, type, categoryId, limit).map { it.toMediaItem() }
    }

    suspend fun queryMediaPaged(
        accountId: String,
        type: String,
        categoryId: String,
        query: String,
        minRating: Double,
        year: String,
        limit: Int,
        offset: Int
    ): List<MediaItem> = withContext(Dispatchers.IO) {
        mediaDao.queryMediaPaged(accountId, type, categoryId, query, minRating, year, limit, offset).map { it.toMediaItem() }
    }

    suspend fun countMedia(
        accountId: String,
        type: String,
        categoryId: String,
        query: String,
        minRating: Double,
        year: String
    ): Int = withContext(Dispatchers.IO) {
        mediaDao.countMedia(accountId, type, categoryId, query, minRating, year)
    }

    suspend fun getItemsByIds(ids: List<String>): List<MediaItem> = withContext(Dispatchers.IO) {
        mediaDao.getItemsByIds(ids).map { it.toMediaItem() }
    }

    suspend fun searchAll(accountId: String, query: String): List<MediaItem> = withContext(Dispatchers.IO) {
        mediaDao.searchAll(accountId, query).map { it.toMediaItem() }
    }

    suspend fun saveCategories(accountId: String, type: MediaType, categories: List<CategoryEntity>) = withContext(Dispatchers.IO) {
        mediaDao.deleteCategoriesByAccountAndType(accountId, type.name)
        mediaDao.insertCategories(categories)
    }

    suspend fun getCategories(accountId: String, type: MediaType): List<Category> = withContext(Dispatchers.IO) {
        mediaDao.getCategories(accountId, type.name).map { it.toCategory() }
    }

    fun clearCache() {
        prefs.edit().remove("watch_history")
            .remove("cached_movies")
            .remove("cached_series")
            .remove("cached_live")
            .remove("cached_movie_cats")
            .remove("cached_series_cats")
            .remove("cached_live_cats")
            .apply()
        runBlocking(Dispatchers.IO) {
            try {
                mediaDao.clearAll()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // --- Compatibility Methods (Backed by Room without giant JSON strings) ---

    fun cacheMediaList(key: String, items: List<MediaItem>) {
        val account = getActiveAccount()
        val type = when {
            key.contains("movie", ignoreCase = true) -> MediaType.MOVIE
            key.contains("series", ignoreCase = true) -> MediaType.SERIES
            else -> MediaType.LIVE
        }
        val entities = items.map { MediaItemEntity.fromMediaItem(it, account.id) }
        runBlocking(Dispatchers.IO) {
            try {
                mediaDao.deleteByAccountAndType(account.id, type.name)
                mediaDao.insertAll(entities)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun getCachedMediaList(key: String): List<MediaItem> {
        val account = getActiveAccount()
        val type = when {
            key.contains("movie", ignoreCase = true) -> "MOVIE"
            key.contains("series", ignoreCase = true) -> "SERIES"
            else -> "LIVE"
        }
        return runBlocking(Dispatchers.IO) {
            try {
                mediaDao.getTopRated(account.id, type, 50).map { it.toMediaItem() }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    fun cacheCategories(key: String, categories: List<Category>) {
        val account = getActiveAccount()
        val type = when {
            key.contains("movie", ignoreCase = true) -> MediaType.MOVIE
            key.contains("series", ignoreCase = true) -> MediaType.SERIES
            else -> MediaType.LIVE
        }
        val entities = categories.map { CategoryEntity.fromCategory(it, account.id) }
        runBlocking(Dispatchers.IO) {
            try {
                mediaDao.deleteCategoriesByAccountAndType(account.id, type.name)
                mediaDao.insertCategories(entities)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun getCachedCategories(key: String): List<Category> {
        val account = getActiveAccount()
        val type = when {
            key.contains("movie", ignoreCase = true) -> "MOVIE"
            key.contains("series", ignoreCase = true) -> "SERIES"
            else -> "LIVE"
        }
        return runBlocking(Dispatchers.IO) {
            try {
                mediaDao.getCategories(account.id, type).map { it.toCategory() }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
}
