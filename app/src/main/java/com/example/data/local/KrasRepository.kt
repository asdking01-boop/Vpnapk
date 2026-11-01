package com.example.data.local

import android.content.Context
import android.content.SharedPreferences
import com.example.data.model.*
import com.example.data.sample.SampleData
import org.json.JSONArray
import org.json.JSONObject

class KrasRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("kras_tv_prefs", Context.MODE_PRIVATE)

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
        saveFavorites(list)
        return isAdded
    }

    private fun saveFavorites(list: List<FavoriteItem>) {
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
        list.add(0, progress.copy(updatedAt = System.currentTimeMillis()))
        try {
            val array = JSONArray()
            // Keep at most 30 recent items
            for (item in list.take(30)) {
                val obj = JSONObject().apply {
                    put("id", item.id)
                    put("mediaId", item.mediaId)
                    put("title", item.title)
                    put("type", item.type.name)
                    put("posterUrl", item.posterUrl)
                    put("positionMs", item.positionMs)
                    put("durationMs", item.durationMs)
                    item.seasonNum?.let { put("seasonNum", it) }
                    item.episodeNum?.let { put("episodeNum", it) }
                    put("streamUrl", item.streamUrl)
                    put("updatedAt", item.updatedAt)
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
                        progress = obj.optDouble("progress", 0.0).toFloat(),
                        status = DownloadStatus.valueOf(obj.optString("status", "QUEUED")),
                        fileSizeBytes = obj.optLong("fileSizeBytes", 0L),
                        downloadedBytes = obj.optLong("downloadedBytes", 0L),
                        streamUrl = obj.optString("streamUrl", "")
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun saveDownloads(list: List<DownloadItem>) {
        try {
            val array = JSONArray()
            for (item in list) {
                val obj = JSONObject().apply {
                    put("id", item.id)
                    put("mediaId", item.mediaId)
                    put("title", item.title)
                    put("type", item.type.name)
                    put("posterUrl", item.posterUrl)
                    put("progress", item.progress)
                    put("status", item.status.name)
                    put("fileSizeBytes", item.fileSizeBytes)
                    put("downloadedBytes", item.downloadedBytes)
                    put("streamUrl", item.streamUrl)
                }
                array.put(obj)
            }
            prefs.edit().putString("downloads_list", array.toString()).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // --- User Settings ---
    fun getUserSettings(): UserSettings {
        return UserSettings(
            accentColorIndex = prefs.getInt("accentColorIndex", 0),
            blurIntensity = prefs.getString("blurIntensity", "Medium") ?: "Medium",
            cardStyle = try {
                CardStyle.valueOf(prefs.getString("cardStyle", "POSTER") ?: "POSTER")
            } catch (e: Exception) { CardStyle.POSTER },
            defaultQuality = prefs.getString("defaultQuality", "Auto") ?: "Auto",
            bufferSize = prefs.getString("bufferSize", "Large") ?: "Large",
            defaultAspectRatio = try {
                AspectRatioMode.valueOf(prefs.getString("defaultAspectRatio", "FIT") ?: "FIT")
            } catch (e: Exception) { AspectRatioMode.FIT },
            language = prefs.getString("language", "English") ?: "English",
            parentalPin = prefs.getString("parentalPin", "") ?: "",
            parentalEnabled = prefs.getBoolean("parentalEnabled", false),
            playIntroSound = prefs.getBoolean("playIntroSound", true)
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
        }.apply()
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
    }

    // --- Cached Home Content ---
    fun cacheMediaList(key: String, items: List<MediaItem>) {
        try {
            val array = JSONArray()
            for (item in items) {
                val obj = JSONObject().apply {
                    put("id", item.id)
                    put("streamId", item.streamId)
                    put("name", item.name)
                    put("title", item.title)
                    put("type", item.type.name)
                    put("streamIcon", item.streamIcon)
                    put("backdrop", item.backdrop)
                    put("categoryId", item.categoryId)
                    put("categoryName", item.categoryName)
                    put("rating", item.rating)
                    put("year", item.year)
                    put("resolution", item.resolution)
                    put("quality", item.quality)
                    put("plot", item.plot)
                    put("genre", item.genre)
                    put("duration", item.duration)
                    put("streamUrl", item.streamUrl)
                    put("containerExtension", item.containerExtension)
                    put("addedTimestamp", item.addedTimestamp)
                }
                array.put(obj)
            }
            prefs.edit().putString(key, array.toString()).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getCachedMediaList(key: String): List<MediaItem> {
        val jsonStr = prefs.getString(key, null) ?: return emptyList()
        val list = mutableListOf<MediaItem>()
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    MediaItem(
                        id = obj.getString("id"),
                        streamId = obj.optInt("streamId", 0),
                        name = obj.getString("name"),
                        title = obj.optString("title", obj.getString("name")),
                        type = MediaType.valueOf(obj.getString("type")),
                        streamIcon = obj.optString("streamIcon", ""),
                        backdrop = obj.optString("backdrop", ""),
                        categoryId = obj.optString("categoryId", ""),
                        categoryName = obj.optString("categoryName", ""),
                        rating = obj.optDouble("rating", 0.0),
                        year = obj.optString("year", ""),
                        resolution = obj.optString("resolution", "HD"),
                        quality = obj.optString("quality", "HD"),
                        plot = obj.optString("plot", ""),
                        genre = obj.optString("genre", ""),
                        duration = obj.optString("duration", ""),
                        streamUrl = obj.optString("streamUrl", ""),
                        containerExtension = obj.optString("containerExtension", "mp4"),
                        addedTimestamp = obj.optLong("addedTimestamp", System.currentTimeMillis())
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun cacheCategories(key: String, categories: List<Category>) {
        try {
            val array = JSONArray()
            for (cat in categories) {
                val obj = JSONObject().apply {
                    put("categoryId", cat.categoryId)
                    put("categoryName", cat.categoryName)
                    put("parentId", cat.parentId)
                    put("type", cat.type.name)
                }
                array.put(obj)
            }
            prefs.edit().putString(key, array.toString()).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getCachedCategories(key: String): List<Category> {
        val jsonStr = prefs.getString(key, null) ?: return emptyList()
        val list = mutableListOf<Category>()
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    Category(
                        categoryId = obj.getString("categoryId"),
                        categoryName = obj.getString("categoryName"),
                        parentId = obj.optInt("parentId", 0),
                        type = MediaType.valueOf(obj.getString("type"))
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }
}
