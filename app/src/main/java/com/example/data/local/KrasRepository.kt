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
                        createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                        activationDate = obj.optString("activationDate", "")
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
                    put("activationDate", acc.activationDate)
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

    fun updateAccountSubscription(updatedAccount: Account) {
        val existing = getAccounts()
        if (existing.none { it.id == updatedAccount.id }) return
        val updated = existing.map { acc ->
            if (acc.id == updatedAccount.id) {
                acc.copy(
                    status = updatedAccount.status,
                    expDate = updatedAccount.expDate,
                    activationDate = updatedAccount.activationDate.ifBlank { acc.activationDate },
                    maxConnections = updatedAccount.maxConnections.ifBlank { acc.maxConnections }
                )
            } else {
                acc
            }
        }
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
    private fun getFavoritesRaw(): List<FavoriteItem> {
        val jsonStr = prefs.getString("favorites_list", null) ?: return emptyList()
        val list = mutableListOf<FavoriteItem>()
        val tracker = ChannelNameNormalizer.ChannelGroupingTracker()
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val title = obj.getString("title")
                val computed = tracker.nextChannel(title)
                val savedGroupKey = obj.optString("groupKey", "").ifEmpty { computed.groupKey }
                val savedSeq = if (obj.has("seq")) obj.optInt("seq", computed.seq) else computed.seq
                val savedGroupOrder = if (obj.has("groupOrder") && obj.optInt("groupOrder", -1) >= 0) {
                    obj.optInt("groupOrder", computed.groupOrder)
                } else {
                    computed.groupOrder
                }
                val savedServerOrder = if (obj.has("serverOrder") && obj.optInt("serverOrder", -1) >= 0) {
                    obj.optInt("serverOrder", computed.serverOrder)
                } else {
                    computed.serverOrder
                }
                list.add(
                    FavoriteItem(
                        id = obj.getString("id"),
                        mediaId = obj.getString("mediaId"),
                        type = MediaType.valueOf(obj.getString("type")),
                        title = title,
                        posterUrl = obj.optString("posterUrl", ""),
                        rating = obj.optDouble("rating", 0.0),
                        year = obj.optString("year", ""),
                        categoryName = obj.optString("categoryName", ""),
                        streamUrl = obj.optString("streamUrl", ""),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                        groupKey = savedGroupKey,
                        seq = savedSeq,
                        groupOrder = savedGroupOrder,
                        serverOrder = savedServerOrder,
                        quality = obj.optString("quality", "").ifBlank {
                            QualityDetector.detectFromNameAndCategory(title, obj.optString("categoryName", ""))
                        }
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun getFavorites(order: LiveChannelOrder = getUserSettings().liveChannelOrder): List<FavoriteItem> {
        val raw = getFavoritesRaw()
        return ChannelNameNormalizer.sortFavorites(raw, order)
    }

    fun toggleFavorite(item: MediaItem): Boolean {
        val list = getFavoritesRaw().toMutableList()
        val existing = list.find { it.mediaId == item.id }
        val isAdded: Boolean
        if (existing != null) {
            list.remove(existing)
            isAdded = false
        } else {
            val norm = ChannelNameNormalizer.normalize(item.name)
            val resolvedQuality = item.quality.ifBlank { item.resolution }.ifBlank {
                QualityDetector.detectFromNameAndCategory(item.name, item.categoryName)
            }
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
                    streamUrl = item.streamUrl,
                    groupKey = item.groupKey.ifBlank { norm.groupKey },
                    seq = if (item.groupKey.isNotBlank()) item.seq else norm.seq,
                    groupOrder = item.groupOrder,
                    serverOrder = item.serverOrder,
                    quality = resolvedQuality
                )
            )
            isAdded = true
        }
        // Recompute groupOrder/serverOrder across the raw favorite list if needed
        val tracker = ChannelNameNormalizer.ChannelGroupingTracker()
        val updatedList = list.map { fav ->
            val computed = tracker.nextChannel(fav.title)
            if (fav.groupOrder == 0 && fav.serverOrder == 0) {
                fav.copy(
                    groupKey = computed.groupKey,
                    seq = computed.seq,
                    groupOrder = computed.groupOrder,
                    serverOrder = computed.serverOrder
                )
            } else {
                fav
            }
        }
        saveFavoritesList(updatedList)
        return isAdded
    }

    private fun saveFavoritesList(updatedList: List<FavoriteItem>) {
        try {
            val array = JSONArray()
            for (fav in updatedList) {
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
                    put("groupKey", fav.groupKey)
                    put("seq", fav.seq)
                    put("groupOrder", fav.groupOrder)
                    put("serverOrder", fav.serverOrder)
                    put("quality", fav.quality)
                }
                array.put(obj)
            }
            prefs.edit().putString("favorites_list", array.toString()).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun isFavorite(mediaId: String): Boolean {
        return getFavoritesRaw().any { it.mediaId == mediaId }
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
        val orderName = prefs.getString("liveChannelOrder", LiveChannelOrder.SMART.name) ?: LiveChannelOrder.SMART.name
        val liveOrder = try {
            LiveChannelOrder.valueOf(orderName)
        } catch (e: Exception) {
            LiveChannelOrder.SMART
        }

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
            silenceNotificationsWhileWatching = prefs.getBoolean("silenceNotificationsWhileWatching", false),
            liveChannelOrder = liveOrder,
            autoCheckUpdates = prefs.getBoolean("autoCheckUpdates", true),
            backgroundPlayback = prefs.getBoolean("backgroundPlayback", true),
            liveSwipeGesturesEnabled = prefs.getBoolean("liveSwipeGesturesEnabled", true)
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
            putString("liveChannelOrder", settings.liveChannelOrder.name)
            putBoolean("autoCheckUpdates", settings.autoCheckUpdates)
            putBoolean("backgroundPlayback", settings.backgroundPlayback)
            putBoolean("liveSwipeGesturesEnabled", settings.liveSwipeGesturesEnabled)
        }.apply()
    }

    // --- Recently Watched & Last Watched Live TV Channels ---
    fun recordRecentLiveChannel(channel: MediaItem) {
        try {
            val current = getRecentLiveChannels().filter { it.id != channel.id }.toMutableList()
            current.add(0, channel)
            val trimmed = current.take(20)
            val array = JSONArray()
            for (ch in trimmed) {
                val obj = JSONObject().apply {
                    put("id", ch.id)
                    put("streamId", ch.streamId)
                    put("name", ch.name)
                    put("title", ch.title.ifBlank { ch.name })
                    put("streamIcon", ch.streamIcon)
                    put("backdrop", ch.backdrop)
                    put("categoryId", ch.categoryId)
                    put("categoryName", ch.categoryName)
                    put("quality", ch.quality)
                    put("resolution", ch.resolution)
                    put("plot", ch.plot)
                    put("streamUrl", ch.streamUrl)
                }
                array.put(obj)
            }
            prefs.edit()
                .putString("recent_live_channels", array.toString())
                .putString("last_watched_live_channel_id", channel.id)
                .apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getRecentLiveChannels(): List<MediaItem> {
        val json = prefs.getString("recent_live_channels", null) ?: return emptyList()
        val list = mutableListOf<MediaItem>()
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val q = obj.optString("quality", "")
                val r = obj.optString("resolution", q)
                list.add(
                    MediaItem(
                        id = obj.getString("id"),
                        streamId = obj.optInt("streamId", 0),
                        name = obj.optString("name", "Channel"),
                        title = obj.optString("title", obj.optString("name", "Channel")),
                        type = MediaType.LIVE,
                        streamIcon = obj.optString("streamIcon", ""),
                        backdrop = obj.optString("backdrop", ""),
                        categoryId = obj.optString("categoryId", ""),
                        categoryName = obj.optString("categoryName", ""),
                        quality = q,
                        resolution = r,
                        plot = obj.optString("plot", ""),
                        streamUrl = obj.optString("streamUrl", "")
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun getLastWatchedLiveChannel(): MediaItem? {
        val recent = getRecentLiveChannels()
        val lastId = prefs.getString("last_watched_live_channel_id", null)
        return if (!lastId.isNullOrBlank()) {
            recent.find { it.id == lastId } ?: recent.firstOrNull()
        } else {
            recent.firstOrNull()
        }
    }

    fun getLastUpdateCheckMs(): Long {
        return prefs.getLong("last_update_check_ms", 0L)
    }

    fun saveLastUpdateCheckMs(timestampMs: Long) {
        prefs.edit().putLong("last_update_check_ms", timestampMs).apply()
    }

    fun getLastKnownVersionCode(): Int {
        return prefs.getInt("last_known_version_code", -1)
    }

    fun saveLastKnownVersionCode(versionCode: Int) {
        prefs.edit().putInt("last_known_version_code", versionCode).apply()
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
        if (items.isEmpty()) return@withContext
        val needsOrderComputation = items.size > 1 && items.all { it.serverOrder == 0 && it.groupOrder == 0 }
        val tracker = if (needsOrderComputation) ChannelNameNormalizer.ChannelGroupingTracker() else null

        val processed = items.map { item ->
            val canonicalQuality = MediaItemEntity.computeCanonicalQuality(
                name = item.name,
                categoryName = item.categoryName,
                explicitQuality = item.quality,
                explicitResolution = item.resolution
            )
            if (tracker != null) {
                val info = tracker.nextChannel(item.name)
                item.copy(
                    groupKey = info.groupKey,
                    seq = info.seq,
                    groupOrder = info.groupOrder,
                    serverOrder = info.serverOrder,
                    quality = canonicalQuality,
                    resolution = canonicalQuality
                )
            } else if (item.groupKey.isEmpty()) {
                val norm = ChannelNameNormalizer.normalize(item.name)
                item.copy(
                    groupKey = norm.groupKey,
                    seq = norm.seq,
                    quality = canonicalQuality,
                    resolution = canonicalQuality
                )
            } else if (item.quality != canonicalQuality || item.resolution != canonicalQuality) {
                item.copy(
                    quality = canonicalQuality,
                    resolution = canonicalQuality
                )
            } else {
                item
            }
        }
        mediaDao.insertAll(processed)
    }

    suspend fun updateMediaQuality(id: String, quality: String) = withContext(Dispatchers.IO) {
        if (id.isBlank() || quality.isBlank()) return@withContext
        try {
            mediaDao.updateMediaQuality(id, quality)
            val favs = getFavoritesRaw()
            if (favs.any { it.mediaId == id }) {
                val updatedFavs = favs.map { fav ->
                    if (fav.mediaId == id) fav.copy(quality = quality) else fav
                }
                saveFavoritesList(updatedFavs)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun clearOldRows(accountId: String, type: String) = withContext(Dispatchers.IO) {
        mediaDao.deleteByAccountAndType(accountId, type)
    }

    suspend fun clearAllMedia() = withContext(Dispatchers.IO) {
        mediaDao.clearAll()
    }

    suspend fun getTopRated(accountId: String, type: String, limit: Int = 20): List<MediaItem> = withContext(Dispatchers.IO) {
        if (type.equals("LIVE", ignoreCase = true)) {
            queryMediaPaged(accountId, "LIVE", "all", "", 0.0, "All", limit, 0)
        } else {
            mediaDao.getTopRated(accountId, type, limit).map { it.toMediaItem() }
        }
    }

    suspend fun getRecentlyAdded(accountId: String, limit: Int = 20): List<MediaItem> = withContext(Dispatchers.IO) {
        mediaDao.getRecentlyAdded(accountId, limit).map { it.toMediaItem() }
    }

    suspend fun getByCategory(accountId: String, type: String, categoryId: String, limit: Int = 15): List<MediaItem> = withContext(Dispatchers.IO) {
        if (type.equals("LIVE", ignoreCase = true)) {
            queryMediaPaged(accountId, "LIVE", categoryId, "", 0.0, "All", limit, 0)
        } else {
            mediaDao.getByCategory(accountId, type, categoryId, limit).map { it.toMediaItem() }
        }
    }

    suspend fun getLatestByCategory(
        accountId: String,
        type: String,
        categoryId: String,
        limit: Int = 20
    ): List<MediaItem> = withContext(Dispatchers.IO) {
        try {
            val rawEntities = mediaDao.getLatestByCategory(accountId, type, categoryId, limit * 2)
            val seenIds = HashSet<String>()
            val seenStreamIds = HashSet<Int>()
            val seenTitles = HashSet<String>()
            val deduplicated = ArrayList<MediaItem>(limit)
            for (entity in rawEntities) {
                val item = entity.toMediaItem()
                if (!seenIds.add(item.id)) continue
                if (item.streamId > 0 && !seenStreamIds.add(item.streamId)) continue
                val titleKey = "${item.categoryId}_${item.name.trim().lowercase()}"
                if (!seenTitles.add(titleKey)) continue
                deduplicated.add(item)
                if (deduplicated.size >= limit) break
            }
            deduplicated
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun getLatestForAllCategories(
        accountId: String,
        type: String,
        categories: List<Category>,
        limitPerCategory: Int = 20
    ): Map<String, List<MediaItem>> = withContext(Dispatchers.IO) {
        val result = LinkedHashMap<String, List<MediaItem>>()
        result["all"] = getLatestByCategory(accountId, type, "all", limitPerCategory)
        for (cat in categories) {
            result[cat.categoryId] = getLatestByCategory(accountId, type, cat.categoryId, limitPerCategory)
        }
        result
    }

    suspend fun getAvailableYears(
        accountId: String,
        type: String,
        categoryId: String = "all"
    ): List<String> = withContext(Dispatchers.IO) {
        try {
            mediaDao.getAvailableYears(accountId, type, categoryId)
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun queryMediaPaged(
        accountId: String,
        type: String,
        categoryId: String,
        query: String,
        minRating: Double,
        year: String,
        limit: Int,
        offset: Int,
        liveChannelOrder: LiveChannelOrder = getUserSettings().liveChannelOrder,
        qualityFilter: String = "All",
        sortByQuality: Boolean = false,
        sortOrder: MediaSortOrder = MediaSortOrder.RECENTLY_ADDED
    ): List<MediaItem> = withContext(Dispatchers.IO) {
        if (type.equals("LIVE", ignoreCase = true)) {
            if (categoryId == "favorites") {
                val favIds = getFavoritesRaw().filter { it.type == MediaType.LIVE }.map { it.mediaId }
                if (favIds.isEmpty()) return@withContext emptyList()
                val favEntities = when (liveChannelOrder) {
                    LiveChannelOrder.SMART -> mediaDao.getFavoritesSmart(accountId, favIds, query, qualityFilter, sortByQuality)
                    LiveChannelOrder.SERVER -> mediaDao.getFavoritesServer(accountId, favIds, query, qualityFilter, sortByQuality)
                    LiveChannelOrder.A_TO_Z -> mediaDao.getFavoritesAZ(accountId, favIds, query, qualityFilter, sortByQuality)
                }
                return@withContext favEntities.drop(offset).take(limit).map { it.toMediaItem() }
            }
            if (categoryId == "recent") {
                val recentList = getRecentLiveChannels().filter { ch ->
                    (query.isBlank() || ch.name.contains(query, ignoreCase = true)) &&
                            QualityDetector.matchesQualityFilter(ch.quality.ifBlank { ch.resolution }, qualityFilter)
                }
                val sorted = if (sortByQuality) QualityDetector.sortMediaByQualityDescending(recentList) else recentList
                return@withContext sorted.drop(offset).take(limit)
            }

            val entities = when (liveChannelOrder) {
                LiveChannelOrder.SMART -> mediaDao.queryLiveSmartPaged(accountId, categoryId, query, limit, offset, qualityFilter, sortByQuality)
                LiveChannelOrder.SERVER -> mediaDao.queryLiveServerPaged(accountId, categoryId, query, limit, offset, qualityFilter, sortByQuality)
                LiveChannelOrder.A_TO_Z -> mediaDao.queryLiveAZPaged(accountId, categoryId, query, limit, offset, qualityFilter, sortByQuality)
            }
            entities.map { it.toMediaItem() }
        } else {
            mediaDao.queryMediaPaged(
                accountId = accountId,
                type = type,
                categoryId = categoryId,
                query = query,
                minRating = minRating,
                year = year,
                limit = limit,
                offset = offset,
                qualityFilter = qualityFilter,
                sortByQuality = sortByQuality,
                sortOrder = sortOrder.name
            ).map { it.toMediaItem() }
        }
    }


    suspend fun countMedia(
        accountId: String,
        type: String,
        categoryId: String,
        query: String,
        minRating: Double,
        year: String,
        qualityFilter: String = "All"
    ): Int = withContext(Dispatchers.IO) {
        if (type.equals("LIVE", ignoreCase = true) && categoryId == "favorites") {
            val favIds = getFavoritesRaw().filter { it.type == MediaType.LIVE }.map { it.mediaId }
            if (favIds.isEmpty()) return@withContext 0
            return@withContext mediaDao.getFavoritesSmart(accountId, favIds, query, qualityFilter, false).size
        }
        if (type.equals("LIVE", ignoreCase = true) && categoryId == "recent") {
            return@withContext getRecentLiveChannels().count { ch ->
                (query.isBlank() || ch.name.contains(query, ignoreCase = true)) &&
                        QualityDetector.matchesQualityFilter(ch.quality.ifBlank { ch.resolution }, qualityFilter)
            }
        }
        mediaDao.countMedia(accountId, type, categoryId, query, minRating, year, qualityFilter)
    }

    suspend fun getItemsByIds(ids: List<String>): List<MediaItem> = withContext(Dispatchers.IO) {
        mediaDao.getItemsByIds(ids).map { it.toMediaItem() }
    }

    suspend fun searchAll(
        accountId: String,
        query: String,
        liveChannelOrder: LiveChannelOrder = getUserSettings().liveChannelOrder
    ): List<MediaItem> = withContext(Dispatchers.IO) {
        val entities = when (liveChannelOrder) {
            LiveChannelOrder.SMART -> mediaDao.searchAll(accountId, query)
            LiveChannelOrder.SERVER -> mediaDao.searchAllServer(accountId, query)
            LiveChannelOrder.A_TO_Z -> mediaDao.searchAllAZ(accountId, query)
        }
        entities.map { it.toMediaItem() }
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
        val normalizedItems = items.map { it.copy(type = type) }
        val entities = MediaItemEntity.fromMediaItemList(normalizedItems, account.id)
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
                if (type == "LIVE") {
                    queryMediaPaged(account.id, "LIVE", "all", "", 0.0, "All", 200, 0)
                } else {
                    mediaDao.getTopRated(account.id, type, 50).map { it.toMediaItem() }
                }
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
