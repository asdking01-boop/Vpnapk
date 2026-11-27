package com.example.data.local

import android.content.Context
import com.example.data.api.TmdbCastMember
import com.example.data.api.TmdbEpisodeMetadata
import com.example.data.api.TmdbErrorSanitizer
import com.example.data.api.TmdbMediaMetadata
import com.example.data.api.TmdbMetadataMatcher
import com.example.data.api.TmdbPersonBiography
import com.example.data.api.TmdbSeasonSummary
import com.example.data.api.TmdbSimilarItem
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent manual TMDb match override chosen by the user for a specific IPTV media item.
 */
data class ManualTmdbBinding(
    val mediaItemId: String,
    val tmdbId: Int,
    val isSeries: Boolean,
    val matchedTitle: String,
    val boundAtMs: Long
)

/**
 * Persistent local cache & telemetry store for rich TMDb metadata.
 *
 * Responsibilities:
 * - Persists rich TMDb metadata (backdrop, poster, synopsis, cast with actor photos, director,
 *   runtime, releaseDate, official trailer key, similar titles, seasons) to local app storage
 *   without modifying the Room database schema.
 * - Serves cached metadata immediately on Details screen open, refreshes stale entries in the
 *   background, and preserves existing cached metadata if the API temporarily fails.
 * - Persists user manual TMDb match bindings across restarts and catalog syncs.
 * - Tracks last successful API request timestamp and sanitized last error for Connection Diagnostics.
 */
class TmdbMetadataCacheStore(context: Context) {

    private val appContext = context.applicationContext
    private val cacheFile = File(appContext.filesDir, CACHE_FILE_NAME)
    private val telemetryPrefs = appContext.getSharedPreferences(TELEMETRY_PREFS_NAME, Context.MODE_PRIVATE)

    private val lock = Any()
    private var loaded = false
    private val entriesByKey = LinkedHashMap<String, TmdbMediaMetadata>()
    private val manualBindings = LinkedHashMap<String, ManualTmdbBinding>()
    private val personBiographiesByKey = LinkedHashMap<String, TmdbPersonBiography>()
    private val seasonEpisodesByKey = LinkedHashMap<String, List<TmdbEpisodeMetadata>>()
    private val negativeAttemptMs = HashMap<String, Long>()

    private fun ensureLoadedLocked() {
        if (loaded) return
        loaded = true
        if (!cacheFile.exists()) return
        try {
            val raw = cacheFile.readText(Charsets.UTF_8)
            if (raw.isBlank()) return
            val root = JSONObject(raw)

            val entriesObj = root.optJSONObject("entries")
            if (entriesObj != null) {
                val keys = entriesObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val obj = entriesObj.optJSONObject(key) ?: continue
                    val parsed = parseMetadata(obj)
                    if (parsed.tmdbId > 0 || parsed.overview.isNotBlank() || parsed.backdropUrl.isNotBlank()) {
                        entriesByKey[key] = parsed
                    }
                }
            }

            val manualObj = root.optJSONObject("manualBindings")
            if (manualObj != null) {
                val keys = manualObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val obj = manualObj.optJSONObject(key) ?: continue
                    val tmdbId = obj.optInt("tmdbId", 0)
                    if (tmdbId > 0) {
                        manualBindings[key] = ManualTmdbBinding(
                            mediaItemId = key,
                            tmdbId = tmdbId,
                            isSeries = obj.optBoolean("isSeries", false),
                            matchedTitle = obj.optString("matchedTitle", ""),
                            boundAtMs = obj.optLong("boundAtMs", 0L)
                        )
                    }
                }
            }

            val personsObj = root.optJSONObject("personBiographies")
            if (personsObj != null) {
                val keys = personsObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val obj = personsObj.optJSONObject(key) ?: continue
                    val pid = obj.optInt("personId", 0)
                    if (pid > 0) {
                        personBiographiesByKey[key] = TmdbPersonBiography(
                            personId = pid,
                            name = obj.optString("name", ""),
                            character = obj.optString("character", ""),
                            biography = obj.optString("biography", ""),
                            birthday = obj.optString("birthday", ""),
                            placeOfBirth = obj.optString("placeOfBirth", ""),
                            knownForDepartment = obj.optString("knownForDepartment", ""),
                            profileUrl = obj.optString("profileUrl", ""),
                            languageTag = obj.optString("languageTag", TmdbMetadataMatcher.TMDB_LANG_ARABIC),
                            cachedAtMs = obj.optLong("cachedAtMs", 0L)
                        )
                    }
                }
            }

            val episodesObj = root.optJSONObject("seasonEpisodes")
            if (episodesObj != null) {
                val keys = episodesObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val arr = episodesObj.optJSONArray(key) ?: continue
                    val list = mutableListOf<TmdbEpisodeMetadata>()
                    for (i in 0 until arr.length()) {
                        val e = arr.optJSONObject(i) ?: continue
                        list.add(
                            TmdbEpisodeMetadata(
                                episodeNumber = e.optInt("episodeNumber", i + 1),
                                seasonNumber = e.optInt("seasonNumber", 1),
                                title = e.optString("title", ""),
                                overview = e.optString("overview", ""),
                                stillUrl = e.optString("stillUrl", ""),
                                rating = e.optDouble("rating", 0.0).let { if (it.isNaN()) 0.0 else it },
                                durationSec = e.optInt("durationSec", 0),
                                airDate = e.optString("airDate", "")
                            )
                        )
                    }
                    if (list.isNotEmpty()) {
                        seasonEpisodesByKey[key] = list
                    }
                }
            }
        } catch (_: Exception) {
            // Preserve app stability if cache file is unreadable; will rewrite cleanly on next save.
        }
    }

    private fun persistLocked() {
        try {
            // Trim oldest non-manual entries if cache grows beyond limit
            while (entriesByKey.size > MAX_CACHE_ENTRIES) {
                val oldestKey = entriesByKey.entries
                    .firstOrNull { !it.value.isManualMatch }
                    ?.key ?: entriesByKey.keys.firstOrNull() ?: break
                entriesByKey.remove(oldestKey)
            }

            val root = JSONObject()
            val entriesObj = JSONObject()
            for ((k, v) in entriesByKey) {
                entriesObj.put(k, serializeMetadata(v))
            }
            root.put("entries", entriesObj)

            val manualObj = JSONObject()
            for ((k, b) in manualBindings) {
                manualObj.put(
                    k,
                    JSONObject().apply {
                        put("tmdbId", b.tmdbId)
                        put("isSeries", b.isSeries)
                        put("matchedTitle", b.matchedTitle)
                        put("boundAtMs", b.boundAtMs)
                    }
                )
            }
            root.put("manualBindings", manualObj)

            while (personBiographiesByKey.size > 200) {
                val oldest = personBiographiesByKey.keys.firstOrNull() ?: break
                personBiographiesByKey.remove(oldest)
            }
            val personsObj = JSONObject()
            for ((k, p) in personBiographiesByKey) {
                personsObj.put(
                    k,
                    JSONObject().apply {
                        put("personId", p.personId)
                        put("name", p.name)
                        put("character", p.character)
                        put("biography", p.biography)
                        put("birthday", p.birthday)
                        put("placeOfBirth", p.placeOfBirth)
                        put("knownForDepartment", p.knownForDepartment)
                        put("profileUrl", p.profileUrl)
                        put("languageTag", p.languageTag)
                        put("cachedAtMs", p.cachedAtMs)
                    }
                )
            }
            root.put("personBiographies", personsObj)

            while (seasonEpisodesByKey.size > 160) {
                val oldest = seasonEpisodesByKey.keys.firstOrNull() ?: break
                seasonEpisodesByKey.remove(oldest)
            }
            val episodesObj = JSONObject()
            for ((k, eps) in seasonEpisodesByKey) {
                val arr = JSONArray()
                eps.forEach { e ->
                    arr.put(
                        JSONObject().apply {
                            put("episodeNumber", e.episodeNumber)
                            put("seasonNumber", e.seasonNumber)
                            put("title", e.title)
                            put("overview", e.overview)
                            put("stillUrl", e.stillUrl)
                            put("rating", e.rating)
                            put("durationSec", e.durationSec)
                            put("airDate", e.airDate)
                        }
                    )
                }
                episodesObj.put(k, arr)
            }
            root.put("seasonEpisodes", episodesObj)

            val tmp = File(cacheFile.parentFile, "$CACHE_FILE_NAME.tmp")
            tmp.writeText(root.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(cacheFile)) {
                cacheFile.writeText(root.toString(), Charsets.UTF_8)
                tmp.delete()
            }
        } catch (_: Exception) {
        }
    }

    fun buildItemKey(mediaItemId: String, preferArabic: Boolean): String {
        val lang = if (preferArabic) TmdbMetadataMatcher.TMDB_LANG_ARABIC else TmdbMetadataMatcher.TMDB_LANG_ENGLISH
        return "item:${mediaItemId.trim()}:$lang"
    }

    fun buildTmdbKey(tmdbId: Int, isSeries: Boolean, preferArabic: Boolean): String {
        val type = if (isSeries) "tv" else "movie"
        val lang = if (preferArabic) TmdbMetadataMatcher.TMDB_LANG_ARABIC else TmdbMetadataMatcher.TMDB_LANG_ENGLISH
        return "tmdb:$type:$tmdbId:$lang"
    }

    fun buildPersonKey(personId: Int, preferArabic: Boolean): String {
        val lang = if (preferArabic) TmdbMetadataMatcher.TMDB_LANG_ARABIC else TmdbMetadataMatcher.TMDB_LANG_ENGLISH
        return "person:$personId:$lang"
    }

    fun buildSeasonEpisodesKey(tmdbId: Int, seasonNumber: Int, preferArabic: Boolean): String {
        val lang = if (preferArabic) TmdbMetadataMatcher.TMDB_LANG_ARABIC else TmdbMetadataMatcher.TMDB_LANG_ENGLISH
        return "season:$tmdbId:$seasonNumber:$lang"
    }

    private fun buildLegacyItemKey(mediaItemId: String, preferArabic: Boolean): String {
        val lang = if (preferArabic) "ar" else "en"
        return "item:${mediaItemId.trim()}:$lang"
    }

    private fun buildLegacyTmdbKey(tmdbId: Int, isSeries: Boolean, preferArabic: Boolean): String {
        val type = if (isSeries) "tv" else "movie"
        val lang = if (preferArabic) "ar" else "en"
        return "tmdb:$type:$tmdbId:$lang"
    }

    fun getForMediaItem(mediaItemId: String, tmdbId: Int, isSeries: Boolean, preferArabic: Boolean): TmdbMediaMetadata? {
        synchronized(lock) {
            ensureLoadedLocked()
            getExactLanguageLocked(mediaItemId, tmdbId, isSeries, preferArabic)?.let { return it }
            // Fallback to opposite language cached entry ONLY for instant display while preferred language refreshes
            getExactLanguageLocked(mediaItemId, tmdbId, isSeries, !preferArabic)?.let { return it }
            return null
        }
    }

    fun getExactLanguageForMediaItem(mediaItemId: String, tmdbId: Int, isSeries: Boolean, preferArabic: Boolean): TmdbMediaMetadata? {
        synchronized(lock) {
            ensureLoadedLocked()
            return getExactLanguageLocked(mediaItemId, tmdbId, isSeries, preferArabic)
        }
    }

    private fun getExactLanguageLocked(mediaItemId: String, tmdbId: Int, isSeries: Boolean, preferArabic: Boolean): TmdbMediaMetadata? {
        entriesByKey[buildItemKey(mediaItemId, preferArabic)]?.let { return it }
        entriesByKey[buildLegacyItemKey(mediaItemId, preferArabic)]?.let { return it }
        if (tmdbId > 0) {
            entriesByKey[buildTmdbKey(tmdbId, isSeries, preferArabic)]?.let { return it }
            entriesByKey[buildLegacyTmdbKey(tmdbId, isSeries, preferArabic)]?.let { return it }
        }
        return null
    }

    fun getPersonBiography(personId: Int, preferArabic: Boolean): TmdbPersonBiography? {
        synchronized(lock) {
            ensureLoadedLocked()
            return personBiographiesByKey[buildPersonKey(personId, preferArabic)]
        }
    }

    fun putPersonBiography(personId: Int, preferArabic: Boolean, bio: TmdbPersonBiography, nowMs: Long = System.currentTimeMillis()) {
        if (personId <= 0) return
        synchronized(lock) {
            ensureLoadedLocked()
            val key = buildPersonKey(personId, preferArabic)
            personBiographiesByKey.remove(key)
            personBiographiesByKey[key] = bio.copy(cachedAtMs = if (bio.cachedAtMs > 0L) bio.cachedAtMs else nowMs)
            persistLocked()
        }
    }

    fun getSeasonEpisodes(tmdbId: Int, seasonNumber: Int, preferArabic: Boolean): List<TmdbEpisodeMetadata> {
        if (tmdbId <= 0 || seasonNumber < 1) return emptyList()
        synchronized(lock) {
            ensureLoadedLocked()
            return seasonEpisodesByKey[buildSeasonEpisodesKey(tmdbId, seasonNumber, preferArabic)].orEmpty()
        }
    }

    fun putSeasonEpisodes(tmdbId: Int, seasonNumber: Int, preferArabic: Boolean, episodes: List<TmdbEpisodeMetadata>) {
        if (tmdbId <= 0 || seasonNumber < 1 || episodes.isEmpty()) return
        synchronized(lock) {
            ensureLoadedLocked()
            val key = buildSeasonEpisodesKey(tmdbId, seasonNumber, preferArabic)
            seasonEpisodesByKey.remove(key)
            seasonEpisodesByKey[key] = episodes
            persistLocked()
        }
    }

    fun putForMediaItem(
        mediaItemId: String,
        isSeries: Boolean,
        preferArabic: Boolean,
        metadata: TmdbMediaMetadata,
        nowMs: Long = System.currentTimeMillis()
    ) {
        synchronized(lock) {
            ensureLoadedLocked()
            val stamped = metadata.copy(cachedAtMs = if (metadata.cachedAtMs > 0L) metadata.cachedAtMs else nowMs)
            val itemKey = buildItemKey(mediaItemId, preferArabic)
            entriesByKey.remove(itemKey)
            entriesByKey[itemKey] = stamped
            if (stamped.tmdbId > 0) {
                val tmdbKey = buildTmdbKey(stamped.tmdbId, isSeries, preferArabic)
                entriesByKey.remove(tmdbKey)
                entriesByKey[tmdbKey] = stamped
            }
            negativeAttemptMs.remove(itemKey)
            persistLocked()
        }
    }

    fun isStale(metadata: TmdbMediaMetadata?, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (metadata == null) return true
        if (metadata.cachedAtMs <= 0L) return true
        return (nowMs - metadata.cachedAtMs) >= STALE_TTL_MS
    }

    fun isBiographyStale(biography: TmdbPersonBiography?, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (biography == null) return true
        if (biography.cachedAtMs <= 0L) return true
        return (nowMs - biography.cachedAtMs) >= STALE_TTL_MS
    }

    fun isRichMetadataComplete(metadata: TmdbMediaMetadata?): Boolean {
        if (metadata == null || metadata.tmdbId <= 0) return false
        val hasOverview = metadata.overview.isNotBlank()
        val hasArtwork = metadata.backdropUrl.isNotBlank() || metadata.posterUrl.isNotBlank()
        val hasCredits = metadata.castMembers.isNotEmpty() || metadata.cast.isNotBlank()
        return hasOverview && hasArtwork && hasCredits
    }

    fun saveManualBinding(binding: ManualTmdbBinding) {
        synchronized(lock) {
            ensureLoadedLocked()
            manualBindings[binding.mediaItemId] = binding
            // Invalidate old cached item entries so fresh bound TMDb details replace them cleanly
            entriesByKey.remove(buildItemKey(binding.mediaItemId, preferArabic = false))
            entriesByKey.remove(buildItemKey(binding.mediaItemId, preferArabic = true))
            negativeAttemptMs.remove(buildItemKey(binding.mediaItemId, preferArabic = false))
            negativeAttemptMs.remove(buildItemKey(binding.mediaItemId, preferArabic = true))
            persistLocked()
        }
    }

    fun getManualBinding(mediaItemId: String): ManualTmdbBinding? {
        synchronized(lock) {
            ensureLoadedLocked()
            return manualBindings[mediaItemId]
        }
    }

    fun clearManualBinding(mediaItemId: String) {
        synchronized(lock) {
            ensureLoadedLocked()
            manualBindings.remove(mediaItemId)
            persistLocked()
        }
    }

    fun shouldSkipAfterRecentFailure(mediaItemId: String, preferArabic: Boolean, nowMs: Long = System.currentTimeMillis()): Boolean {
        synchronized(lock) {
            val ts = negativeAttemptMs[buildItemKey(mediaItemId, preferArabic)] ?: return false
            return (nowMs - ts) < NEGATIVE_BACKOFF_MS
        }
    }

    fun markLookupFailure(mediaItemId: String, preferArabic: Boolean, nowMs: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            negativeAttemptMs[buildItemKey(mediaItemId, preferArabic)] = nowMs
        }
    }

    fun clearNegativeBackoffs() {
        synchronized(lock) {
            negativeAttemptMs.clear()
        }
    }

    /**
     * Marks cached entries as stale (without deleting them!) so cached data remains visible
     * immediately while fresh metadata is fetched on next open or manual refresh.
     */
    fun markAllEntriesStaleForRefresh() {
        synchronized(lock) {
            ensureLoadedLocked()
            negativeAttemptMs.clear()
            for (key in entriesByKey.keys.toList()) {
                val existing = entriesByKey[key] ?: continue
                entriesByKey[key] = existing.copy(cachedAtMs = 1L)
            }
            persistLocked()
        }
    }

    fun clearAllCache() {
        synchronized(lock) {
            entriesByKey.clear()
            personBiographiesByKey.clear()
            seasonEpisodesByKey.clear()
            negativeAttemptMs.clear()
            persistLocked()
        }
    }

    fun getCachedEntryCount(): Int = synchronized(lock) {
        ensureLoadedLocked()
        entriesByKey.keys.count { it.startsWith("item:") }
    }

    fun getManualMatchCount(): Int = synchronized(lock) {
        ensureLoadedLocked()
        manualBindings.size
    }

    // --- Diagnostics Telemetry (strictly sanitized, never stores secrets) ---

    fun recordApiSuccess(endpointLabel: String = "", nowMs: Long = System.currentTimeMillis()) {
        telemetryPrefs.edit()
            .putLong(KEY_LAST_SUCCESS_MS, nowMs)
            .putString(KEY_LAST_SUCCESS_ENDPOINT, endpointLabel.take(64))
            .apply()
    }

    fun recordApiError(rawError: String?, nowMs: Long = System.currentTimeMillis()) {
        val sanitized = TmdbErrorSanitizer.sanitize(rawError)
        if (sanitized.isBlank()) return
        telemetryPrefs.edit()
            .putString(KEY_LAST_ERROR, sanitized)
            .putLong(KEY_LAST_ERROR_MS, nowMs)
            .apply()
    }

    fun clearLastApiError() {
        telemetryPrefs.edit()
            .remove(KEY_LAST_ERROR)
            .remove(KEY_LAST_ERROR_MS)
            .apply()
    }

    fun getLastSuccessfulRequestMs(): Long = telemetryPrefs.getLong(KEY_LAST_SUCCESS_MS, 0L)

    fun getLastSanitizedError(): String = telemetryPrefs.getString(KEY_LAST_ERROR, "").orEmpty()

    private fun serializeMetadata(m: TmdbMediaMetadata): JSONObject {
        return JSONObject().apply {
            put("tmdbId", m.tmdbId)
            put("title", m.title)
            put("originalTitle", m.originalTitle)
            put("overview", m.overview)
            put("posterUrl", m.posterUrl)
            put("backdropUrl", m.backdropUrl)
            put("rating", m.rating)
            put("releaseYear", m.releaseYear)
            put("releaseDate", m.releaseDate)
            put("genres", m.genres)
            put("cast", m.cast)
            put("director", m.director)
            put("runtimeMinutes", m.runtimeMinutes)
            put("trailerYoutubeKey", m.trailerYoutubeKey)
            put("trailerLanguage", m.trailerLanguage)
            put("cachedAtMs", m.cachedAtMs)
            put("isManualMatch", m.isManualMatch)

            val castArr = JSONArray()
            m.castMembers.forEach { c ->
                castArr.put(
                    JSONObject().apply {
                        put("id", c.id)
                        put("name", c.name)
                        put("character", c.character)
                        put("profileUrl", c.profileUrl)
                    }
                )
            }
            put("castMembers", castArr)

            val simArr = JSONArray()
            m.similarItems.forEach { s ->
                simArr.put(
                    JSONObject().apply {
                        put("tmdbId", s.tmdbId)
                        put("title", s.title)
                        put("originalTitle", s.originalTitle)
                        put("posterUrl", s.posterUrl)
                        put("backdropUrl", s.backdropUrl)
                        put("releaseYear", s.releaseYear)
                        put("rating", s.rating)
                        put("isSeries", s.isSeries)
                    }
                )
            }
            put("similarItems", simArr)

            val seasonsArr = JSONArray()
            m.seasons.forEach { sn ->
                seasonsArr.put(
                    JSONObject().apply {
                        put("seasonNumber", sn.seasonNumber)
                        put("name", sn.name)
                        put("episodeCount", sn.episodeCount)
                        put("overview", sn.overview)
                        put("posterUrl", sn.posterUrl)
                        put("airDate", sn.airDate)
                    }
                )
            }
            put("seasons", seasonsArr)
        }
    }

    private fun parseMetadata(obj: JSONObject): TmdbMediaMetadata {
        val castMembers = mutableListOf<TmdbCastMember>()
        val castArr = obj.optJSONArray("castMembers")
        if (castArr != null) {
            for (i in 0 until castArr.length()) {
                val c = castArr.optJSONObject(i) ?: continue
                val name = c.optString("name", "").trim()
                if (name.isNotEmpty()) {
                    castMembers.add(
                        TmdbCastMember(
                            id = c.optInt("id", 0),
                            name = name,
                            character = c.optString("character", "").trim(),
                            profileUrl = c.optString("profileUrl", "").trim()
                        )
                    )
                }
            }
        }

        val similarItems = mutableListOf<TmdbSimilarItem>()
        val simArr = obj.optJSONArray("similarItems")
        if (simArr != null) {
            for (i in 0 until simArr.length()) {
                val s = simArr.optJSONObject(i) ?: continue
                val tid = s.optInt("tmdbId", 0)
                val title = s.optString("title", "").trim()
                if (tid > 0 && title.isNotEmpty()) {
                    similarItems.add(
                        TmdbSimilarItem(
                            tmdbId = tid,
                            title = title,
                            originalTitle = s.optString("originalTitle", "").trim(),
                            posterUrl = s.optString("posterUrl", "").trim(),
                            backdropUrl = s.optString("backdropUrl", "").trim(),
                            releaseYear = s.optString("releaseYear", "").trim(),
                            rating = s.optDouble("rating", 0.0).let { if (it.isNaN()) 0.0 else it },
                            isSeries = s.optBoolean("isSeries", false)
                        )
                    )
                }
            }
        }

        val seasons = mutableListOf<TmdbSeasonSummary>()
        val seasonsArr = obj.optJSONArray("seasons")
        if (seasonsArr != null) {
            for (i in 0 until seasonsArr.length()) {
                val sn = seasonsArr.optJSONObject(i) ?: continue
                val num = sn.optInt("seasonNumber", 0)
                if (num >= 1) {
                    seasons.add(
                        TmdbSeasonSummary(
                            seasonNumber = num,
                            name = sn.optString("name", "Season $num"),
                            episodeCount = sn.optInt("episodeCount", 0),
                            overview = sn.optString("overview", ""),
                            posterUrl = sn.optString("posterUrl", ""),
                            airDate = sn.optString("airDate", "")
                        )
                    )
                }
            }
        }

        return TmdbMediaMetadata(
            tmdbId = obj.optInt("tmdbId", 0),
            title = obj.optString("title", ""),
            originalTitle = obj.optString("originalTitle", ""),
            overview = obj.optString("overview", ""),
            posterUrl = obj.optString("posterUrl", ""),
            backdropUrl = obj.optString("backdropUrl", ""),
            rating = obj.optDouble("rating", 0.0).let { if (it.isNaN()) 0.0 else it },
            releaseYear = obj.optString("releaseYear", ""),
            releaseDate = obj.optString("releaseDate", ""),
            genres = obj.optString("genres", ""),
            cast = obj.optString("cast", ""),
            director = obj.optString("director", ""),
            runtimeMinutes = obj.optInt("runtimeMinutes", 0),
            seasons = seasons,
            castMembers = castMembers,
            trailerYoutubeKey = obj.optString("trailerYoutubeKey", ""),
            trailerLanguage = obj.optString("trailerLanguage", ""),
            similarItems = similarItems,
            cachedAtMs = obj.optLong("cachedAtMs", 0L),
            isManualMatch = obj.optBoolean("isManualMatch", false)
        )
    }

    companion object {
        private const val CACHE_FILE_NAME = "tmdb_rich_metadata_cache_v1.json"
        private const val TELEMETRY_PREFS_NAME = "tmdb_diagnostics_telemetry_prefs"
        private const val KEY_LAST_SUCCESS_MS = "last_success_ms"
        private const val KEY_LAST_SUCCESS_ENDPOINT = "last_success_endpoint"
        private const val KEY_LAST_ERROR = "last_sanitized_error"
        private const val KEY_LAST_ERROR_MS = "last_error_ms"

        const val STALE_TTL_MS: Long = 7L * 24L * 60L * 60L * 1000L // 7 days
        const val NEGATIVE_BACKOFF_MS: Long = 10L * 60L * 1000L // 10 minutes
        private const val MAX_CACHE_ENTRIES = 600
    }
}
