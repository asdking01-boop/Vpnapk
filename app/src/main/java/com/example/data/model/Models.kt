package com.example.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

enum class MediaType {
    LIVE, MOVIE, SERIES
}

enum class CardStyle {
    POSTER, WIDE, COMPACT, BILLBOARD
}

enum class AspectRatioMode {
    FIT, FILL, STRETCH, RATIO_16_9, RATIO_4_3
}

enum class DownloadStatus {
    QUEUED, DOWNLOADING, COMPLETED, FAILED
}

enum class LiveChannelOrder(val label: String) {
    SMART("Smart (group similar channels, numeric order)"),
    SERVER("Server order"),
    A_TO_Z("A to Z")
}

enum class MediaSortOrder(val label: String, val shortLabel: String) {
    RECENTLY_ADDED("Recently Added – Newest First", "Recently Added"),
    YEAR_NEWEST("Release Year – Newest to Oldest", "Year: Newest"),
    YEAR_OLDEST("Release Year – Oldest to Newest", "Year: Oldest"),
    TITLE_AZ("Title – A to Z", "Title: A–Z"),
    TITLE_ZA("Title – Z to A", "Title: Z–A")
}


data class Account(
    val id: String,
    val name: String,
    val serverUrl: String,
    val username: String,
    val password: String,
    val isActive: Boolean = false,
    val status: String = "Active",
    val expDate: String = "",
    val maxConnections: String = "1",
    val isDemo: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

data class MediaItem(
    val id: String,
    val streamId: Int,
    val name: String,
    val title: String = name,
    val type: MediaType,
    val streamIcon: String = "",
    val backdrop: String = "",
    val categoryId: String = "",
    val categoryName: String = "",
    val rating: Double = 0.0,
    val year: String = "",
    val resolution: String = "",
    val quality: String = "",
    val plot: String = "",
    val genre: String = "",
    val duration: String = "",
    val streamUrl: String = "",
    val containerExtension: String = "mp4",
    val addedTimestamp: Long = System.currentTimeMillis(),
    val groupKey: String = "",
    val seq: Int = 0,
    val groupOrder: Int = 0,
    val serverOrder: Int = 0
)

data class Category(
    val categoryId: String,
    val categoryName: String,
    val parentId: Int = 0,
    val type: MediaType = MediaType.MOVIE
)

data class Season(
    val seasonNumber: Int,
    val name: String,
    val episodeCount: Int,
    val episodes: List<Episode> = emptyList()
)

data class Episode(
    val id: String,
    val episodeNum: Int,
    val title: String,
    val containerExtension: String = "mp4",
    val plot: String = "",
    val durationSec: Int = 0,
    val rating: Double = 0.0,
    val coverUrl: String = "",
    val streamUrl: String = "",
    val seriesId: Int = 0,
    val seasonNumber: Int = 1,
    val quality: String = ""
)

data class SeriesDetails(
    val seriesId: Int,
    val name: String,
    val cover: String = "",
    val backdrop: String = "",
    val plot: String = "",
    val cast: String = "",
    val director: String = "",
    val genre: String = "",
    val releaseDate: String = "",
    val rating: Double = 0.0,
    val seasons: List<Season> = emptyList(),
    val quality: String = ""
)

data class WatchProgress(
    val id: String,
    val mediaId: String,
    val title: String,
    val type: MediaType,
    val posterUrl: String,
    val positionMs: Long,
    val durationMs: Long,
    val seasonNum: Int? = null,
    val episodeNum: Int? = null,
    val streamUrl: String = "",
    val updatedAt: Long = System.currentTimeMillis()
) {
    val progressFraction: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
}

data class FavoriteItem(
    val id: String,
    val mediaId: String,
    val type: MediaType,
    val title: String,
    val posterUrl: String,
    val rating: Double,
    val year: String,
    val categoryName: String,
    val streamUrl: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val groupKey: String = "",
    val seq: Int = 0,
    val groupOrder: Int = 0,
    val serverOrder: Int = 0,
    val quality: String = ""
)

data class DownloadItem(
    val id: String,
    val mediaId: String,
    val title: String,
    val type: MediaType,
    val posterUrl: String,
    val progress: Float = 0f,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val fileSizeBytes: Long = 0L,
    val downloadedBytes: Long = 0L,
    val streamUrl: String = ""
)

data class UserSettings(
    val accentColorIndex: Int = 0, // 0: Red, 1: Cyan, 2: Gold, 3: Emerald, 4: Purple
    val blurIntensity: String = "Medium", // Off, Low, Medium, High
    val cardStyle: CardStyle = CardStyle.POSTER,
    val defaultQuality: String = "Auto", // Auto, 4K, 1080p, 720p
    val bufferSize: String = "Large", // Standard (15s), Large (30s), Aggressive (60s)
    val defaultAspectRatio: AspectRatioMode = AspectRatioMode.FIT,
    val language: String = "English", // English, Arabic
    val parentalPin: String = "",
    val parentalEnabled: Boolean = false,
    val playIntroSound: Boolean = true,
    val silenceNotificationsWhileWatching: Boolean = false,
    val liveChannelOrder: LiveChannelOrder = LiveChannelOrder.SMART,
    val autoCheckUpdates: Boolean = true,
    val backgroundPlayback: Boolean = true
)

// Xtream Codes API DTOs
@JsonClass(generateAdapter = true)
data class XtreamAuthResponse(
    @Json(name = "user_info") val userInfo: XtreamUserInfo? = null,
    @Json(name = "server_info") val serverInfo: XtreamServerInfo? = null
)

@JsonClass(generateAdapter = true)
data class XtreamUserInfo(
    @Json(name = "username") val username: String? = null,
    @Json(name = "status") val status: String? = null,
    @Json(name = "exp_date") val expDate: String? = null,
    @Json(name = "is_trial") val isTrial: String? = null,
    @Json(name = "active_cons") val activeCons: String? = null,
    @Json(name = "max_connections") val maxConnections: String? = null,
    @Json(name = "allowed_output_formats") val allowedFormats: List<String>? = null
)

@JsonClass(generateAdapter = true)
data class XtreamServerInfo(
    @Json(name = "url") val url: String? = null,
    @Json(name = "port") val port: String? = null,
    @Json(name = "https_port") val httpsPort: String? = null,
    @Json(name = "server_protocol") val serverProtocol: String? = null,
    @Json(name = "timezone") val timezone: String? = null
)

@JsonClass(generateAdapter = true)
data class XtreamCategoryDto(
    @Json(name = "category_id") val categoryId: String? = null,
    @Json(name = "category_name") val categoryName: String? = null,
    @Json(name = "parent_id") val parentId: Int? = null
)

@JsonClass(generateAdapter = true)
data class XtreamLiveStreamDto(
    @Json(name = "stream_id") val streamId: Int? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "stream_type") val streamType: String? = null,
    @Json(name = "stream_icon") val streamIcon: String? = null,
    @Json(name = "category_id") val categoryId: String? = null,
    @Json(name = "epg_channel_id") val epgChannelId: String? = null,
    @Json(name = "added") val added: String? = null
)

@JsonClass(generateAdapter = true)
data class XtreamVodStreamDto(
    @Json(name = "stream_id") val streamId: Int? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "stream_icon") val streamIcon: String? = null,
    @Json(name = "category_id") val categoryId: String? = null,
    @Json(name = "container_extension") val containerExtension: String? = null,
    @Json(name = "rating") val rating: Any? = null, // String or Double in different servers
    @Json(name = "year") val year: String? = null,
    @Json(name = "added") val added: String? = null
)

@JsonClass(generateAdapter = true)
data class XtreamSeriesDto(
    @Json(name = "series_id") val seriesId: Int? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "cover") val cover: String? = null,
    @Json(name = "plot") val plot: String? = null,
    @Json(name = "cast") val cast: String? = null,
    @Json(name = "director") val director: String? = null,
    @Json(name = "genre") val genre: String? = null,
    @Json(name = "releaseDate") val releaseDate: String? = null,
    @Json(name = "rating") val rating: Any? = null,
    @Json(name = "category_id") val categoryId: String? = null,
    @Json(name = "backdrop_path") val backdropPath: List<String>? = null
)
