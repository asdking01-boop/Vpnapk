package com.example.data.local

import android.content.Context
import android.content.SharedPreferences
import android.util.LruCache
import com.example.data.api.TmdbDirectClient
import com.example.data.api.TmdbProxyClient
import com.example.data.api.XtreamClient
import com.example.data.local.db.KrasDatabase
import com.example.data.local.entity.CategoryEntity
import com.example.data.local.entity.EnrichedMediaEntity
import com.example.data.local.entity.MediaItemEntity
import com.example.data.model.*
import com.example.data.sample.SampleData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class KrasRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("kras_tv_prefs", Context.MODE_PRIVATE)

    private val db = KrasDatabase.getInstance(context)
    val mediaDao = db.mediaDao()

    init {
        migrateStoredTmdbProxyUrl()
        migrateLegacyGlobalSyncListsIfPresent()
    }

    private fun resolveUserKey(krasAccountId: String? = null): String {
        val explicit = krasAccountId?.trim().orEmpty()
        val id = explicit.ifEmpty { getActiveAccount().id.trim() }.ifEmpty { "demo" }
        return id.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
    }

    private fun favoritesKeyForUser(krasAccountId: String? = null): String =
        "favorites_list_v2_${resolveUserKey(krasAccountId)}"

    private fun watchlistKeyForUser(krasAccountId: String? = null): String =
        "tmdb_watchlist_items_v2_${resolveUserKey(krasAccountId)}"

    private fun ratingsKeyForUser(krasAccountId: String? = null): String =
        "tmdb_user_ratings_v2_${resolveUserKey(krasAccountId)}"

    private fun pendingMutationsKeyForUser(krasAccountId: String? = null): String =
        "tmdb_pending_mutations_v2_${resolveUserKey(krasAccountId)}"

    /**
     * Migrates legacy un-scoped favorites/watchlist/ratings keys to the active user's scoped keys
     * and removes the legacy un-scoped keys so users never share TMDb sync lists.
     */
    private fun migrateLegacyGlobalSyncListsIfPresent() {
        val activeUserKey = resolveUserKey(null)
        val editor = prefs.edit()
        var migratedAny = false

        val legacyFavs = prefs.getString("favorites_list", null)
        if (legacyFavs != null) {
            val targetKey = "favorites_list_v2_$activeUserKey"
            if (!prefs.contains(targetKey)) {
                editor.putString(targetKey, legacyFavs)
            }
            editor.remove("favorites_list")
            migratedAny = true
        }

        val legacyWatchlist = prefs.getString("tmdb_watchlist_items", null)
        if (legacyWatchlist != null) {
            val targetKey = "tmdb_watchlist_items_v2_$activeUserKey"
            if (!prefs.contains(targetKey)) {
                editor.putString(targetKey, legacyWatchlist)
            }
            editor.remove("tmdb_watchlist_items")
            migratedAny = true
        }

        val legacyRatings = prefs.getString("tmdb_user_ratings", null)
        if (legacyRatings != null) {
            val targetKey = "tmdb_user_ratings_v2_$activeUserKey"
            if (!prefs.contains(targetKey)) {
                editor.putString(targetKey, legacyRatings)
            }
            editor.remove("tmdb_user_ratings")
            migratedAny = true
        }

        if (migratedAny) {
            editor.apply()
        }
    }

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

    // --- Favorites (Scoped Per KRAS TV User) ---
    private fun getFavoritesRaw(krasAccountId: String? = null): List<FavoriteItem> {
        val jsonStr = prefs.getString(favoritesKeyForUser(krasAccountId), null) ?: return emptyList()
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
                        },
                        tmdbId = obj.optInt("tmdbId", 0)
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return deduplicateFavorites(list)
    }

    private fun deduplicateFavorites(items: List<FavoriteItem>): List<FavoriteItem> {
        val map = LinkedHashMap<String, FavoriteItem>()
        for (fav in items) {
            val key = if (fav.type != MediaType.LIVE && fav.tmdbId > 0) {
                "${fav.type.name}:tmdb:${fav.tmdbId}"
            } else {
                "${fav.type.name}:id:${fav.mediaId}"
            }
            val existing = map[key]
            if (existing == null) {
                map[key] = fav
            } else if (existing.streamUrl.isBlank() && fav.streamUrl.isNotBlank()) {
                map[key] = fav
            }
        }
        return map.values.toList()
    }

    fun getFavorites(
        order: LiveChannelOrder = getUserSettings().liveChannelOrder,
        krasAccountId: String? = null
    ): List<FavoriteItem> {
        val raw = getFavoritesRaw(krasAccountId)
        return ChannelNameNormalizer.sortFavorites(raw, order)
    }

    fun toggleFavorite(item: MediaItem, krasAccountId: String? = null): Boolean {
        val userKey = resolveUserKey(krasAccountId)
        val list = getFavoritesRaw(userKey).toMutableList()
        val existing = list.find {
            it.mediaId == item.id ||
                (item.type != MediaType.LIVE && item.tmdbId > 0 && it.tmdbId == item.tmdbId && it.type == item.type)
        }
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
                    quality = resolvedQuality,
                    tmdbId = item.tmdbId
                )
            )
            isAdded = true
        }
        val tracker = ChannelNameNormalizer.ChannelGroupingTracker()
        val updatedList = deduplicateFavorites(list).map { fav ->
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
        saveFavoritesList(updatedList, userKey)

        if (item.type == MediaType.MOVIE || item.type == MediaType.SERIES) {
            recordPendingTmdbMutation(
                PendingTmdbMutation(
                    krasAccountId = userKey,
                    tmdbId = item.tmdbId.takeIf { it > 0 } ?: (existing?.tmdbId ?: 0),
                    mediaId = item.id,
                    mediaType = item.type.name,
                    title = item.name,
                    year = item.year,
                    category = TmdbMutationCategory.FAVORITE,
                    action = if (isAdded) TmdbMutationAction.ADD else TmdbMutationAction.REMOVE
                )
            )
        }
        return isAdded
    }

    private fun saveFavoritesList(updatedList: List<FavoriteItem>, krasAccountId: String? = null) {
        try {
            val deduplicated = deduplicateFavorites(updatedList)
            val array = JSONArray()
            for (fav in deduplicated) {
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
                    put("tmdbId", fav.tmdbId)
                }
                array.put(obj)
            }
            prefs.edit().putString(favoritesKeyForUser(krasAccountId), array.toString()).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun isFavorite(mediaId: String, krasAccountId: String? = null): Boolean {
        return getFavoritesRaw(krasAccountId).any { it.mediaId == mediaId }
    }

    // --- Watchlist (Scoped Per KRAS TV User + TMDb Two-Way Sync) ---
    fun getWatchlist(krasAccountId: String? = null): List<TmdbWatchlistItem> {
        val jsonStr = prefs.getString(watchlistKeyForUser(krasAccountId), null) ?: return emptyList()
        val list = mutableListOf<TmdbWatchlistItem>()
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    TmdbWatchlistItem(
                        id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                        mediaId = obj.optString("mediaId", ""),
                        tmdbId = obj.optInt("tmdbId", 0),
                        type = try { MediaType.valueOf(obj.optString("type", "MOVIE")) } catch (_: Exception) { MediaType.MOVIE },
                        title = obj.optString("title", ""),
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
        return deduplicateWatchlist(list)
    }

    private fun deduplicateWatchlist(items: List<TmdbWatchlistItem>): List<TmdbWatchlistItem> {
        val map = LinkedHashMap<String, TmdbWatchlistItem>()
        for (item in items) {
            val key = if (item.tmdbId > 0) {
                "${item.type.name}:tmdb:${item.tmdbId}"
            } else {
                "${item.type.name}:id:${item.mediaId.ifBlank { item.title.lowercase() }}"
            }
            if (!map.containsKey(key)) {
                map[key] = item
            }
        }
        return map.values.toList()
    }

    private fun saveWatchlist(items: List<TmdbWatchlistItem>, krasAccountId: String? = null) {
        try {
            val deduplicated = deduplicateWatchlist(items)
            val array = JSONArray()
            for (item in deduplicated) {
                val obj = JSONObject().apply {
                    put("id", item.id)
                    put("mediaId", item.mediaId)
                    put("tmdbId", item.tmdbId)
                    put("type", item.type.name)
                    put("title", item.title)
                    put("posterUrl", item.posterUrl)
                    put("rating", item.rating)
                    put("year", item.year)
                    put("categoryName", item.categoryName)
                    put("streamUrl", item.streamUrl)
                    put("timestamp", item.timestamp)
                }
                array.put(obj)
            }
            prefs.edit().putString(watchlistKeyForUser(krasAccountId), array.toString()).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun toggleWatchlist(item: MediaItem, krasAccountId: String? = null): Boolean {
        val userKey = resolveUserKey(krasAccountId)
        val list = getWatchlist(userKey).toMutableList()
        val existing = list.find {
            it.mediaId == item.id || (item.tmdbId > 0 && it.tmdbId == item.tmdbId && it.type == item.type)
        }
        val isAdded: Boolean
        if (existing != null) {
            list.remove(existing)
            isAdded = false
        } else {
            list.add(
                0,
                TmdbWatchlistItem(
                    id = java.util.UUID.randomUUID().toString(),
                    mediaId = item.id,
                    tmdbId = item.tmdbId,
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
        saveWatchlist(list, userKey)

        if (item.type == MediaType.MOVIE || item.type == MediaType.SERIES) {
            recordPendingTmdbMutation(
                PendingTmdbMutation(
                    krasAccountId = userKey,
                    tmdbId = item.tmdbId.takeIf { it > 0 } ?: (existing?.tmdbId ?: 0),
                    mediaId = item.id,
                    mediaType = item.type.name,
                    title = item.name,
                    year = item.year,
                    category = TmdbMutationCategory.WATCHLIST,
                    action = if (isAdded) TmdbMutationAction.ADD else TmdbMutationAction.REMOVE
                )
            )
        }
        return isAdded
    }

    fun isInWatchlist(mediaId: String, krasAccountId: String? = null): Boolean {
        return getWatchlist(krasAccountId).any { it.mediaId == mediaId }
    }

    // --- User Ratings (Scoped Per KRAS TV User + TMDb Two-Way Sync) ---
    fun getUserRatings(krasAccountId: String? = null): List<TmdbUserRatingItem> {
        val jsonStr = prefs.getString(ratingsKeyForUser(krasAccountId), null) ?: return emptyList()
        val list = mutableListOf<TmdbUserRatingItem>()
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    TmdbUserRatingItem(
                        mediaId = obj.optString("mediaId", ""),
                        tmdbId = obj.optInt("tmdbId", 0),
                        type = try { MediaType.valueOf(obj.optString("type", "MOVIE")) } catch (_: Exception) { MediaType.MOVIE },
                        title = obj.optString("title", ""),
                        posterUrl = obj.optString("posterUrl", ""),
                        year = obj.optString("year", ""),
                        userRating = obj.optDouble("userRating", 0.0),
                        updatedAt = obj.optLong("updatedAt", System.currentTimeMillis())
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return deduplicateRatings(list)
    }

    private fun deduplicateRatings(items: List<TmdbUserRatingItem>): List<TmdbUserRatingItem> {
        val map = LinkedHashMap<String, TmdbUserRatingItem>()
        for (item in items) {
            val key = if (item.tmdbId > 0) {
                "${item.type.name}:tmdb:${item.tmdbId}"
            } else {
                "${item.type.name}:id:${item.mediaId.ifBlank { item.title.lowercase() }}"
            }
            val existing = map[key]
            if (existing == null || item.updatedAt >= existing.updatedAt) {
                map[key] = item
            }
        }
        return map.values.toList()
    }

    private fun saveUserRatings(items: List<TmdbUserRatingItem>, krasAccountId: String? = null) {
        try {
            val deduplicated = deduplicateRatings(items)
            val array = JSONArray()
            for (item in deduplicated) {
                val obj = JSONObject().apply {
                    put("mediaId", item.mediaId)
                    put("tmdbId", item.tmdbId)
                    put("type", item.type.name)
                    put("title", item.title)
                    put("posterUrl", item.posterUrl)
                    put("year", item.year)
                    put("userRating", item.userRating)
                    put("updatedAt", item.updatedAt)
                }
                array.put(obj)
            }
            prefs.edit().putString(ratingsKeyForUser(krasAccountId), array.toString()).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun setUserRating(item: MediaItem, rating: Double, krasAccountId: String? = null) {
        val userKey = resolveUserKey(krasAccountId)
        val clamped = rating.coerceIn(0.5, 10.0)
        val nowMs = System.currentTimeMillis()
        val list = getUserRatings(userKey).filterNot {
            it.mediaId == item.id || (item.tmdbId > 0 && it.tmdbId == item.tmdbId && it.type == item.type)
        }.toMutableList()
        list.add(
            0,
            TmdbUserRatingItem(
                mediaId = item.id,
                tmdbId = item.tmdbId,
                type = item.type,
                title = item.name,
                posterUrl = item.streamIcon,
                year = item.year,
                userRating = clamped,
                updatedAt = nowMs
            )
        )
        saveUserRatings(list, userKey)

        if (item.type == MediaType.MOVIE || item.type == MediaType.SERIES) {
            recordPendingTmdbMutation(
                PendingTmdbMutation(
                    krasAccountId = userKey,
                    tmdbId = item.tmdbId,
                    mediaId = item.id,
                    mediaType = item.type.name,
                    title = item.name,
                    year = item.year,
                    category = TmdbMutationCategory.RATING,
                    action = TmdbMutationAction.RATE,
                    userRating = clamped,
                    updatedAtMs = nowMs
                )
            )
        }
    }

    fun getUserRating(mediaId: String, krasAccountId: String? = null): Double? {
        return getUserRatings(krasAccountId).find { it.mediaId == mediaId }?.userRating
    }

    // --- Offline Mutation Queue & Conflict Tracking (Scoped Per KRAS TV User) ---
    fun getPendingTmdbMutations(krasAccountId: String? = null): List<PendingTmdbMutation> {
        val userKey = resolveUserKey(krasAccountId)
        val jsonStr = prefs.getString(pendingMutationsKeyForUser(userKey), null) ?: return emptyList()
        val list = mutableListOf<PendingTmdbMutation>()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val category = try {
                    TmdbMutationCategory.valueOf(obj.optString("category", "FAVORITE"))
                } catch (_: Exception) {
                    TmdbMutationCategory.FAVORITE
                }
                val action = try {
                    TmdbMutationAction.valueOf(obj.optString("action", "ADD"))
                } catch (_: Exception) {
                    TmdbMutationAction.ADD
                }
                list.add(
                    PendingTmdbMutation(
                        krasAccountId = obj.optString("krasAccountId", userKey),
                        tmdbId = obj.optInt("tmdbId", 0),
                        mediaId = obj.optString("mediaId", ""),
                        mediaType = obj.optString("mediaType", "MOVIE"),
                        title = obj.optString("title", ""),
                        year = obj.optString("year", ""),
                        category = category,
                        action = action,
                        userRating = obj.optDouble("userRating", 0.0),
                        updatedAtMs = obj.optLong("updatedAtMs", 0L)
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun recordPendingTmdbMutation(mutation: PendingTmdbMutation) {
        val userKey = resolveUserKey(mutation.krasAccountId)
        val existing = getPendingTmdbMutations(userKey)
            .filterNot { it.deduplicationKey == mutation.deduplicationKey }
            .toMutableList()
        existing.add(mutation.copy(krasAccountId = userKey))
        savePendingTmdbMutations(existing, userKey)
    }

    fun clearPendingTmdbMutations(krasAccountId: String? = null) {
        val userKey = resolveUserKey(krasAccountId)
        prefs.edit().remove(pendingMutationsKeyForUser(userKey)).apply()
    }

    private fun savePendingTmdbMutations(mutations: List<PendingTmdbMutation>, krasAccountId: String? = null) {
        try {
            val userKey = resolveUserKey(krasAccountId)
            val arr = JSONArray()
            for (m in mutations) {
                arr.put(
                    JSONObject().apply {
                        put("krasAccountId", userKey)
                        put("tmdbId", m.tmdbId)
                        put("mediaId", m.mediaId)
                        put("mediaType", m.normalizedMediaType)
                        put("title", m.title)
                        put("year", m.year)
                        put("category", m.category.name)
                        put("action", m.action.name)
                        put("userRating", m.userRating)
                        put("updatedAtMs", m.updatedAtMs)
                    }
                )
            }
            prefs.edit().putString(pendingMutationsKeyForUser(userKey), arr.toString()).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getLocalTmdbSyncFavorites(krasAccountId: String? = null): List<TmdbSyncItem> {
        return getFavoritesRaw(krasAccountId)
            .filter { it.type == MediaType.MOVIE || it.type == MediaType.SERIES }
            .map { fav ->
                TmdbSyncItem(
                    tmdbId = fav.tmdbId,
                    mediaType = if (fav.type == MediaType.SERIES) "SERIES" else "MOVIE",
                    title = fav.title,
                    posterUrl = fav.posterUrl,
                    releaseYear = fav.year,
                    rating = fav.rating,
                    favorite = true,
                    updatedAtMs = fav.timestamp
                )
            }
    }

    fun getLocalTmdbSyncWatchlist(krasAccountId: String? = null): List<TmdbSyncItem> {
        return getWatchlist(krasAccountId)
            .filter { it.type == MediaType.MOVIE || it.type == MediaType.SERIES }
            .map { wl ->
                TmdbSyncItem(
                    tmdbId = wl.tmdbId,
                    mediaType = if (wl.type == MediaType.SERIES) "SERIES" else "MOVIE",
                    title = wl.title,
                    posterUrl = wl.posterUrl,
                    releaseYear = wl.year,
                    rating = wl.rating,
                    watchlist = true,
                    updatedAtMs = wl.timestamp
                )
            }
    }

    fun getLocalTmdbSyncRatings(krasAccountId: String? = null): List<TmdbSyncItem> {
        return getUserRatings(krasAccountId)
            .filter { (it.type == MediaType.MOVIE || it.type == MediaType.SERIES) && it.userRating >= 0.5 }
            .map { rt ->
                TmdbSyncItem(
                    tmdbId = rt.tmdbId,
                    mediaType = if (rt.type == MediaType.SERIES) "SERIES" else "MOVIE",
                    title = rt.title,
                    posterUrl = rt.posterUrl,
                    releaseYear = rt.year,
                    rating = rt.userRating,
                    userRating = rt.userRating,
                    updatedAtMs = rt.updatedAt
                )
            }
    }

    /**
     * Merges remote TMDb Favorites, Watchlist, and Ratings into local KRAS TV persistence
     * for a specific KRAS TV user (`krasAccountId`) using a deterministic conflict resolution policy:
     * 1. Tombstone Protection: Items explicitly removed locally while offline (`PendingTmdbMutation` with `REMOVE`)
     *    are not resurrected by a stale remote list.
     * 2. Deduplicated Union: Additions from both TMDb and KRAS TV are merged without duplicate records.
     * 3. Latest-Timestamp Wins for Ratings: If an item is rated on both sides, the newer rating wins.
     */
    fun mergeTmdbSyncedData(
        remoteFavorites: List<TmdbSyncItem>,
        remoteWatchlist: List<TmdbSyncItem>,
        remoteRatings: List<TmdbSyncItem>,
        krasAccountId: String? = null
    ) {
        val userKey = resolveUserKey(krasAccountId)
        val pendingMutations = getPendingTmdbMutations(userKey)
        val removedFavTmdbIds = pendingMutations
            .filter { it.category == TmdbMutationCategory.FAVORITE && it.action == TmdbMutationAction.REMOVE }
            .map { "${it.normalizedMediaType}:${it.tmdbId}" }
            .toSet()
        val removedFavTitles = pendingMutations
            .filter { it.category == TmdbMutationCategory.FAVORITE && it.action == TmdbMutationAction.REMOVE && it.title.isNotBlank() }
            .map { "${it.normalizedMediaType}:${it.title.lowercase()}" }
            .toSet()

        val removedWlTmdbIds = pendingMutations
            .filter { it.category == TmdbMutationCategory.WATCHLIST && it.action == TmdbMutationAction.REMOVE }
            .map { "${it.normalizedMediaType}:${it.tmdbId}" }
            .toSet()
        val removedWlTitles = pendingMutations
            .filter { it.category == TmdbMutationCategory.WATCHLIST && it.action == TmdbMutationAction.REMOVE && it.title.isNotBlank() }
            .map { "${it.normalizedMediaType}:${it.title.lowercase()}" }
            .toSet()

        // 1. Merge Favorites (Movies & TV Series)
        val currentFavorites = getFavoritesRaw(userKey).toMutableList()
        var favChanged = false
        for (remote in remoteFavorites) {
            val normType = remote.normalizedMediaType
            if (remote.tmdbId > 0 && "$normType:${remote.tmdbId}" in removedFavTmdbIds) continue
            if (remote.title.isNotBlank() && "$normType:${remote.title.lowercase()}" in removedFavTitles) continue

            val targetType = if (normType == "SERIES") MediaType.SERIES else MediaType.MOVIE
            val existingIdx = currentFavorites.indexOfFirst { fav ->
                fav.type == targetType && (
                    (remote.tmdbId > 0 && fav.tmdbId == remote.tmdbId) ||
                        fav.title.equals(remote.title, ignoreCase = true)
                    )
            }
            if (existingIdx >= 0) {
                val existing = currentFavorites[existingIdx]
                if (existing.tmdbId == 0 && remote.tmdbId > 0) {
                    currentFavorites[existingIdx] = existing.copy(
                        tmdbId = remote.tmdbId,
                        posterUrl = existing.posterUrl.ifBlank { remote.posterUrl },
                        year = existing.year.ifBlank { remote.releaseYear }
                    )
                    favChanged = true
                }
            } else if (remote.title.isNotBlank()) {
                val norm = ChannelNameNormalizer.normalize(remote.title)
                val typeSlug = if (targetType == MediaType.SERIES) "tv" else "movie"
                currentFavorites.add(
                    FavoriteItem(
                        id = java.util.UUID.randomUUID().toString(),
                        mediaId = "tmdb_${typeSlug}_${remote.tmdbId}",
                        type = targetType,
                        title = remote.title,
                        posterUrl = remote.posterUrl,
                        rating = remote.rating,
                        year = remote.releaseYear,
                        categoryName = "TMDb Synced",
                        streamUrl = "",
                        groupKey = norm.groupKey,
                        seq = norm.seq,
                        tmdbId = remote.tmdbId
                    )
                )
                favChanged = true
            }
        }
        if (favChanged) {
            saveFavoritesList(currentFavorites, userKey)
        }

        // 2. Merge Watchlist (Movies & TV Series)
        val currentWatchlist = getWatchlist(userKey).toMutableList()
        var wlChanged = false
        for (remote in remoteWatchlist) {
            val normType = remote.normalizedMediaType
            if (remote.tmdbId > 0 && "$normType:${remote.tmdbId}" in removedWlTmdbIds) continue
            if (remote.title.isNotBlank() && "$normType:${remote.title.lowercase()}" in removedWlTitles) continue

            val targetType = if (normType == "SERIES") MediaType.SERIES else MediaType.MOVIE
            val exists = currentWatchlist.any { wl ->
                wl.type == targetType && (
                    (remote.tmdbId > 0 && wl.tmdbId == remote.tmdbId) ||
                        wl.title.equals(remote.title, ignoreCase = true)
                    )
            }
            if (!exists && remote.title.isNotBlank()) {
                val typeSlug = if (targetType == MediaType.SERIES) "tv" else "movie"
                currentWatchlist.add(
                    TmdbWatchlistItem(
                        id = java.util.UUID.randomUUID().toString(),
                        mediaId = "tmdb_${typeSlug}_${remote.tmdbId}",
                        tmdbId = remote.tmdbId,
                        type = targetType,
                        title = remote.title,
                        posterUrl = remote.posterUrl,
                        rating = remote.rating,
                        year = remote.releaseYear,
                        categoryName = "TMDb Watchlist"
                    )
                )
                wlChanged = true
            }
        }
        if (wlChanged) {
            saveWatchlist(currentWatchlist, userKey)
        }

        // 3. Merge User Ratings (Latest Timestamp Wins)
        val currentRatings = getUserRatings(userKey).toMutableList()
        var ratingsChanged = false
        for (remote in remoteRatings) {
            val effectiveRating = if (remote.userRating > 0.0) remote.userRating else remote.rating
            if (effectiveRating <= 0.0) continue
            val normType = remote.normalizedMediaType
            val targetType = if (normType == "SERIES") MediaType.SERIES else MediaType.MOVIE
            val existingIdx = currentRatings.indexOfFirst { rt ->
                rt.type == targetType && (
                    (remote.tmdbId > 0 && rt.tmdbId == remote.tmdbId) ||
                        rt.title.equals(remote.title, ignoreCase = true)
                    )
            }
            if (existingIdx >= 0) {
                val existing = currentRatings[existingIdx]
                if (remote.updatedAtMs > existing.updatedAt && existing.userRating != effectiveRating) {
                    currentRatings[existingIdx] = existing.copy(
                        tmdbId = if (existing.tmdbId > 0) existing.tmdbId else remote.tmdbId,
                        userRating = effectiveRating,
                        updatedAt = remote.updatedAtMs
                    )
                    ratingsChanged = true
                }
            } else if (remote.title.isNotBlank()) {
                val typeSlug = if (targetType == MediaType.SERIES) "tv" else "movie"
                currentRatings.add(
                    TmdbUserRatingItem(
                        mediaId = "tmdb_${typeSlug}_${remote.tmdbId}",
                        tmdbId = remote.tmdbId,
                        type = targetType,
                        title = remote.title,
                        posterUrl = remote.posterUrl,
                        year = remote.releaseYear,
                        userRating = effectiveRating,
                        updatedAt = remote.updatedAtMs.takeIf { it > 0L } ?: System.currentTimeMillis()
                    )
                )
                ratingsChanged = true
            }
        }
        if (ratingsChanged) {
            saveUserRatings(currentRatings, userKey)
        }

        // Clear replayed offline mutations after successful merge
        clearPendingTmdbMutations(userKey)
    }

    /**
     * Clears TMDb-synced items for a disconnected user while keeping Live TV favorites intact.
     */
    fun clearTmdbSyncedDataOnDisconnect(krasAccountId: String? = null) {
        val userKey = resolveUserKey(krasAccountId)
        val liveOnlyFavs = getFavoritesRaw(userKey).filter { it.type == MediaType.LIVE }
        saveFavoritesList(liveOnlyFavs, userKey)
        prefs.edit()
            .remove(watchlistKeyForUser(userKey))
            .remove(ratingsKeyForUser(userKey))
            .remove(pendingMutationsKeyForUser(userKey))
            .apply()
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
    fun migrateStoredTmdbProxyUrl(): String {
        val rawStored = prefs.getString("tmdbProxyUrl", null)
        val defaultCanonical = com.example.BuildConfig.DEFAULT_TMDB_PROXY_URL.ifBlank {
            TmdbProxyClient.CANONICAL_BACKEND_BASE_URL
        }
        val normalized = TmdbProxyClient.normalizeOrFallbackToCanonical(rawStored, defaultCanonical)
        if (rawStored != normalized) {
            prefs.edit().putString("tmdbProxyUrl", normalized).apply()
        }
        return normalized
    }

    fun getUserSettings(): UserSettings {
        val orderName = prefs.getString("liveChannelOrder", LiveChannelOrder.SMART.name) ?: LiveChannelOrder.SMART.name
        val liveOrder = try {
            LiveChannelOrder.valueOf(orderName)
        } catch (e: Exception) {
            LiveChannelOrder.SMART
        }

        val canonicalProxyUrl = migrateStoredTmdbProxyUrl()

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
            liveSwipeGesturesEnabled = prefs.getBoolean("liveSwipeGesturesEnabled", true),
            tmdbProxyUrl = canonicalProxyUrl,
            tmdbAutoEnrichEnabled = true,
            tmdbAutoSyncEnabled = prefs.getBoolean("tmdbAutoSyncEnabled", true)
        )
    }

    fun saveUserSettings(settings: UserSettings) {
        val defaultCanonical = com.example.BuildConfig.DEFAULT_TMDB_PROXY_URL.ifBlank {
            TmdbProxyClient.CANONICAL_BACKEND_BASE_URL
        }
        val normalizedProxyUrl = TmdbProxyClient.normalizeOrFallbackToCanonical(settings.tmdbProxyUrl, defaultCanonical)
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
            putString("tmdbProxyUrl", normalizedProxyUrl)
            putBoolean("tmdbAutoEnrichEnabled", true)
            putBoolean("tmdbAutoSyncEnabled", settings.tmdbAutoSyncEnabled)
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

    suspend fun updateTmdbMetadata(item: MediaItem) = withContext(Dispatchers.IO) {
        if (item.id.isBlank()) return@withContext
        try {
            mediaDao.updateTmdbMetadata(
                id = item.id,
                posterUrl = item.streamIcon,
                backdropUrl = item.backdrop.ifBlank { item.streamIcon },
                plot = item.plot,
                genre = item.genre,
                cast = item.cast,
                director = item.director,
                rating = item.rating,
                year = item.year,
                tmdbId = item.tmdbId
            )
            val favs = getFavoritesRaw()
            if (favs.any { it.mediaId == item.id }) {
                val updatedFavs = favs.map { fav ->
                    if (fav.mediaId == item.id) {
                        fav.copy(
                            posterUrl = item.streamIcon.ifBlank { fav.posterUrl },
                            rating = if (item.rating > 0.0) item.rating else fav.rating,
                            year = item.year.ifBlank { fav.year }
                        )
                    } else {
                        fav
                    }
                }
                saveFavoritesList(updatedFavs)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun getUnenrichedMedia(
        accountId: String,
        type: String,
        limit: Int = 30
    ): List<MediaItem> = withContext(Dispatchers.IO) {
        try {
            mediaDao.getUnenrichedMedia(accountId, type, limit).map { it.toMediaItem() }
        } catch (e: Exception) {
            emptyList()
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
                val favIds = getFavoritesRaw(accountId).filter { it.type == MediaType.LIVE }.map { it.mediaId }
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
            val favIds = getFavoritesRaw(accountId).filter { it.type == MediaType.LIVE }.map { it.mediaId }
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

    // --- 7-Day Enriched Metadata Cache & Concurrency Limiting ---
    private val memoryDetailsCache = LruCache<String, EnrichedMediaDetails>(120)
    private val enrichmentSemaphore = Semaphore(3)
    private val xtreamClient = XtreamClient()
    private val tmdbDirectClient = TmdbDirectClient()

    fun peekCachedDetails(item: MediaItem): EnrichedMediaDetails? {
        val mediaKey = "${item.type.name.lowercase()}_${item.streamId}"
        synchronized(memoryDetailsCache) {
            val inMem = memoryDetailsCache.get(mediaKey)
            if (inMem != null && (System.currentTimeMillis() - inMem.cachedTimestampMs) < ENRICHMENT_CACHE_TTL_MS) {
                return inMem
            }
        }
        return null
    }

    suspend fun getOrFetchEnrichedMediaDetails(
        item: MediaItem,
        tmdbApiKey: String = "",
        isArabic: Boolean = false,
        accountOverride: Account? = null
    ): EnrichedMediaDetails = withContext(Dispatchers.IO) {
        val mediaKey = "${item.type.name.lowercase()}_${item.streamId}"

        // 1. Check in-memory LRU cache
        synchronized(memoryDetailsCache) {
            val inMemory = memoryDetailsCache.get(mediaKey)
            if (inMemory != null && (System.currentTimeMillis() - inMemory.cachedTimestampMs) < ENRICHMENT_CACHE_TTL_MS) {
                return@withContext inMemory
            }
        }

        // 2. Check Room 7-day cache
        try {
            val cachedEntity = mediaDao.getEnrichedMedia(mediaKey)
            if (cachedEntity != null && (System.currentTimeMillis() - cachedEntity.updatedAtMs) < ENRICHMENT_CACHE_TTL_MS) {
                val parsed = EnrichedMediaDetails.fromJson(cachedEntity.jsonData)
                if (parsed != null) {
                    synchronized(memoryDetailsCache) {
                        memoryDetailsCache.put(mediaKey, parsed)
                    }
                    return@withContext parsed
                }
            }
        } catch (_: Exception) {}

        // 3. Fetch off-main thread with concurrency limit
        enrichmentSemaphore.withPermit {
            // Re-check memory in case another task populated it
            synchronized(memoryDetailsCache) {
                val inMemory = memoryDetailsCache.get(mediaKey)
                if (inMemory != null && (System.currentTimeMillis() - inMemory.cachedTimestampMs) < ENRICHMENT_CACHE_TTL_MS) {
                    return@withContext inMemory
                }
            }

            val activeAcc = accountOverride ?: getActiveAccount()

            // Step a: Xtream details
            var xtreamPlot = item.plot
            var xtreamCast = item.cast
            var xtreamDirector = item.director
            var xtreamGenre = item.genre
            var xtreamReleaseDate = item.year
            var xtreamRating = item.rating
            var xtreamDuration = item.duration
            var xtreamBackdrop = item.backdrop
            var xtreamPoster = item.streamIcon
            var xtreamTrailer = ""
            var xtreamTmdbId = item.tmdbId
            var xtreamQuality = item.quality.ifBlank { item.resolution }
            var seriesSeasons = emptyList<Season>()

            if (!activeAcc.isDemo && activeAcc.serverUrl.isNotBlank()) {
                if (item.type == MediaType.SERIES) {
                    val seriesInfo = xtreamClient.getSeriesDetails(activeAcc, item.streamId)
                    if (seriesInfo != null) {
                        if (seriesInfo.plot.isNotBlank()) xtreamPlot = seriesInfo.plot
                        if (seriesInfo.cast.isNotBlank()) xtreamCast = seriesInfo.cast
                        if (seriesInfo.director.isNotBlank()) xtreamDirector = seriesInfo.director
                        if (seriesInfo.genre.isNotBlank()) xtreamGenre = seriesInfo.genre
                        if (seriesInfo.releaseDate.isNotBlank()) xtreamReleaseDate = seriesInfo.releaseDate
                        if (seriesInfo.rating > 0.0) xtreamRating = seriesInfo.rating
                        if (seriesInfo.cover.isNotBlank()) xtreamPoster = seriesInfo.cover
                        if (seriesInfo.backdrop.isNotBlank()) xtreamBackdrop = seriesInfo.backdrop
                        if (seriesInfo.youtubeTrailer.isNotBlank()) xtreamTrailer = seriesInfo.youtubeTrailer
                        if (seriesInfo.tmdbId > 0) xtreamTmdbId = seriesInfo.tmdbId
                        if (seriesInfo.quality.isNotBlank()) xtreamQuality = seriesInfo.quality
                        seriesSeasons = seriesInfo.seasons
                    }
                } else if (item.type == MediaType.MOVIE) {
                    val vodInfo = xtreamClient.fetchVodDetails(activeAcc, item.streamId, xtreamQuality)
                    if (vodInfo != null) {
                        if (vodInfo.plot.isNotBlank()) xtreamPlot = vodInfo.plot
                        if (vodInfo.cast.isNotBlank()) xtreamCast = vodInfo.cast
                        if (vodInfo.director.isNotBlank()) xtreamDirector = vodInfo.director
                        if (vodInfo.genre.isNotBlank()) xtreamGenre = vodInfo.genre
                        if (vodInfo.releaseDate.isNotBlank()) xtreamReleaseDate = vodInfo.releaseDate
                        if (vodInfo.rating > 0.0) xtreamRating = vodInfo.rating
                        if (vodInfo.streamIcon.isNotBlank()) xtreamPoster = vodInfo.streamIcon
                        if (vodInfo.backdrop.isNotBlank()) xtreamBackdrop = vodInfo.backdrop
                        if (vodInfo.duration.isNotBlank()) xtreamDuration = vodInfo.duration
                        if (vodInfo.youtubeTrailer.isNotBlank()) xtreamTrailer = vodInfo.youtubeTrailer
                        if (vodInfo.tmdbId > 0) xtreamTmdbId = vodInfo.tmdbId
                        if (vodInfo.quality.isNotBlank()) xtreamQuality = vodInfo.quality
                    }
                }
            }

            // Step b: TMDb Match
            var effectiveTmdbId = xtreamTmdbId
            val cleanKey = tmdbApiKey.trim()

            if (effectiveTmdbId <= 0 && cleanKey.isNotEmpty()) {
                val matched = tmdbDirectClient.findTmdbMatch(
                    apiKey = cleanKey,
                    rawTitle = item.name,
                    year = item.year,
                    isSeries = item.type == MediaType.SERIES
                )
                effectiveTmdbId = matched
                // Cache matched tmdbId (including -1 for no match) in Room
                try {
                    mediaDao.updateTmdbId(item.id, effectiveTmdbId)
                } catch (_: Exception) {}
            }

            // Step c: TMDb complete fetch
            var tmdbDetails: TmdbDirectClient.TmdbCompleteDetails? = null
            if (effectiveTmdbId > 0 && cleanKey.isNotEmpty()) {
                tmdbDetails = tmdbDirectClient.fetchCompleteMediaDetails(
                    apiKey = cleanKey,
                    tmdbId = effectiveTmdbId,
                    isSeries = item.type == MediaType.SERIES,
                    language = if (isArabic) "ar-SA" else "en-US"
                )
            }

            // Step d: Merge sources (TMDb wins for poster, backdrop, overview, cast, genres; Xtream fills missing fields)
            val finalPoster = tmdbDetails?.posterUrl?.ifBlank { xtreamPoster } ?: xtreamPoster
            val finalBackdrop = tmdbDetails?.backdropUrl?.ifBlank { xtreamBackdrop.ifBlank { finalPoster } } ?: xtreamBackdrop.ifBlank { finalPoster }
            val finalPlot = tmdbDetails?.overview?.ifBlank { xtreamPlot } ?: xtreamPlot
            val finalYear = tmdbDetails?.releaseYear?.ifBlank { xtreamReleaseDate.take(4).ifBlank { item.year } } ?: xtreamReleaseDate.take(4).ifBlank { item.year }
            val finalReleaseDate = tmdbDetails?.releaseDate?.ifBlank { xtreamReleaseDate } ?: xtreamReleaseDate
            val finalDuration = tmdbDetails?.duration?.ifBlank { xtreamDuration } ?: xtreamDuration
            val finalRating = if (tmdbDetails != null && tmdbDetails.rating > 0.0) tmdbDetails.rating else xtreamRating
            val finalDirector = tmdbDetails?.director?.ifBlank { xtreamDirector } ?: xtreamDirector
            val finalTrailer = tmdbDetails?.youtubeTrailerKey?.ifBlank { xtreamTrailer } ?: xtreamTrailer

            val finalGenres = if (tmdbDetails != null && tmdbDetails.genres.isNotEmpty()) {
                tmdbDetails.genres
            } else if (xtreamGenre.isNotBlank()) {
                xtreamGenre.split(",", ";").map { it.trim() }.filter { it.isNotEmpty() }
            } else {
                emptyList()
            }

            val finalCast = if (tmdbDetails != null && tmdbDetails.cast.isNotEmpty()) {
                tmdbDetails.cast
            } else if (xtreamCast.isNotBlank()) {
                xtreamCast.split(",", ";").mapIndexed { idx, name ->
                    CastMember(id = idx + 1, name = name.trim())
                }.filter { it.name.isNotEmpty() }.take(10)
            } else {
                emptyList()
            }

            val finalSimilar = tmdbDetails?.similar ?: emptyList()

            // User rating / favorite / watchlist status
            val isFav = isFavorite(item.id)
            val isWatchlist = isInWatchlist(item.id)
            val userRatingVal = getUserRating(item.id) ?: 0.0

            val enriched = EnrichedMediaDetails(
                mediaId = item.id,
                streamId = item.streamId,
                tmdbId = effectiveTmdbId.coerceAtLeast(0),
                title = item.name,
                type = item.type,
                posterUrl = finalPoster,
                backdropUrl = finalBackdrop,
                plot = finalPlot,
                year = finalYear,
                releaseDate = finalReleaseDate,
                duration = finalDuration,
                rating = finalRating,
                genres = finalGenres,
                director = finalDirector,
                cast = finalCast,
                youtubeTrailer = finalTrailer,
                quality = xtreamQuality,
                seasons = seriesSeasons,
                similar = finalSimilar,
                userRating = userRatingVal,
                isFavorite = isFav,
                isInWatchlist = isWatchlist,
                cachedTimestampMs = System.currentTimeMillis()
            )

            // Step e: Cache to Room & memory
            try {
                mediaDao.insertEnrichedMedia(
                    EnrichedMediaEntity(
                        mediaKey = mediaKey,
                        streamId = item.streamId,
                        mediaType = item.type.name,
                        tmdbId = effectiveTmdbId,
                        jsonData = enriched.toJson(),
                        updatedAtMs = System.currentTimeMillis()
                    )
                )
            } catch (_: Exception) {}

            synchronized(memoryDetailsCache) {
                memoryDetailsCache.put(mediaKey, enriched)
            }

            enriched
        }
    }

    companion object {
        const val ENRICHMENT_CACHE_TTL_MS = 7L * 24L * 60L * 60L * 1000L // 7 days
    }
}
