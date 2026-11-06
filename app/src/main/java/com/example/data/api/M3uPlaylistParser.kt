package com.example.data.api

import com.example.data.local.ChannelNameNormalizer
import com.example.data.local.QualityDetector
import com.example.data.local.entity.CategoryEntity
import com.example.data.local.entity.MediaItemEntity
import com.example.data.model.Episode
import com.example.data.model.MediaType
import com.example.data.model.Season
import com.example.data.model.SeriesDetails
import java.io.BufferedReader
import java.io.StringReader

data class M3uImportResult(
    val liveCategories: List<CategoryEntity>,
    val movieCategories: List<CategoryEntity>,
    val seriesCategories: List<CategoryEntity>,
    val liveStreams: List<MediaItemEntity>,
    val movies: List<MediaItemEntity>,
    val series: List<MediaItemEntity>,
    val seriesDetailsBySeriesId: Map<Int, SeriesDetails>
) {
    val mediaItems: List<MediaItemEntity>
        get() = liveStreams + movies + series
}

/**
 * Parses standard M3U / M3U_PLUS playlists (`#EXTM3U` / `#EXTINF`) into Live TV, Movies, and TV Series
 * entities (plus grouped seasons and episodes for series) so both Xtream Codes and M3U playlists work
 * seamlessly with automatic TMDb metadata enrichment.
 */
object M3uPlaylistParser {

    private val ATTR_REGEX = Regex("""([a-zA-Z0-9_-]+)\s*=\s*"([^"]*)"""")
    private val SERIES_PATTERN_REGEX =
        Regex("""\bS(\d{1,2})\s*E(\d{1,3})\b|\bSeason\s*(\d{1,2})\s*Episode\s*(\d{1,3})\b""", RegexOption.IGNORE_CASE)
    private val VOD_EXTENSIONS = setOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v")

    fun isM3uPlaylistUrl(url: String): Boolean {
        val lower = url.trim().lowercase()
        return lower.endsWith(".m3u") ||
            lower.endsWith(".m3u8") ||
            lower.contains("type=m3u") ||
            lower.contains("get.php?")
    }

    private data class SeriesAccumulator(
        val entity: MediaItemEntity,
        val episodesBySeason: LinkedHashMap<Int, MutableList<Episode>> = LinkedHashMap()
    )

    fun parse(content: String, accountId: String): M3uImportResult {
        val reader = BufferedReader(StringReader(content))
        val liveCatMap = LinkedHashMap<String, CategoryEntity>()
        val movieCatMap = LinkedHashMap<String, CategoryEntity>()
        val seriesCatMap = LinkedHashMap<String, CategoryEntity>()

        val liveStreams = mutableListOf<MediaItemEntity>()
        val movies = mutableListOf<MediaItemEntity>()
        val seriesAccumulators = LinkedHashMap<String, SeriesAccumulator>()

        val liveTracker = ChannelNameNormalizer.ChannelGroupingTracker()
        val movieTracker = ChannelNameNormalizer.ChannelGroupingTracker()
        val seriesTracker = ChannelNameNormalizer.ChannelGroupingTracker()

        var pendingAttrs: Map<String, String> = emptyMap()
        var pendingTitle = ""
        var autoStreamId = 10_000
        val nowMs = System.currentTimeMillis()

        var line: String? = reader.readLine()
        while (line != null) {
            val trimmed = line.trim()
            if (trimmed.startsWith("#EXTINF:", ignoreCase = true)) {
                val attrs = LinkedHashMap<String, String>()
                ATTR_REGEX.findAll(trimmed).forEach { match ->
                    attrs[match.groupValues[1].lowercase()] = match.groupValues[2].trim()
                }
                val commaIdx = trimmed.lastIndexOf(',')
                pendingTitle = if (commaIdx >= 0 && commaIdx + 1 < trimmed.length) {
                    trimmed.substring(commaIdx + 1).trim()
                } else {
                    attrs["tvg-name"].orEmpty()
                }
                pendingAttrs = attrs
            } else if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                val streamUrl = trimmed
                val rawName = pendingTitle.ifBlank { "Stream ${autoStreamId + 1}" }
                val groupTitle = pendingAttrs["group-title"].orEmpty().ifBlank { "General" }
                val logoUrl = pendingAttrs["tvg-logo"].orEmpty()
                val tmdbIdAttr = pendingAttrs["tvg-id"]?.toIntOrNull()
                    ?: pendingAttrs["tmdb-id"]?.toIntOrNull()
                    ?: 0

                val mediaType = classifyEntryType(rawName, groupTitle, streamUrl)
                val catSlug = groupTitle.lowercase().replace(Regex("""[^a-z0-9]+"""), "_").trim('_').ifBlank { "general" }
                val catId = "${mediaType.name.lowercase()}_$catSlug"

                val catEntity = CategoryEntity(
                    id = "${accountId}_${mediaType.name}_$catId",
                    accountId = accountId,
                    categoryId = catId,
                    categoryName = groupTitle,
                    parentId = 0,
                    type = mediaType.name
                )
                when (mediaType) {
                    MediaType.LIVE -> liveCatMap.putIfAbsent(catId, catEntity)
                    MediaType.MOVIE -> movieCatMap.putIfAbsent(catId, catEntity)
                    MediaType.SERIES -> seriesCatMap.putIfAbsent(catId, catEntity)
                }

                autoStreamId++
                val cleanedQuery = TmdbProxyClient.extractTmdbQuery(rawName)
                val detectedQuality = QualityDetector.detectFromNameAndCategory(rawName, groupTitle)
                val ext = streamUrl.substringBefore('?').substringAfterLast('.', "mp4").lowercase().take(5)

                when (mediaType) {
                    MediaType.LIVE -> {
                        val orderInfo = liveTracker.nextChannel(rawName)
                        liveStreams.add(
                            MediaItemEntity(
                                id = "live_$autoStreamId",
                                accountId = accountId,
                                streamId = autoStreamId,
                                name = rawName,
                                title = cleanedQuery.cleanTitle.ifBlank { rawName },
                                type = MediaType.LIVE.name,
                                posterUrl = logoUrl,
                                backdropUrl = logoUrl,
                                categoryId = catId,
                                categoryName = groupTitle,
                                rating = 0.0,
                                year = cleanedQuery.extractedYear,
                                resolution = detectedQuality,
                                quality = detectedQuality,
                                streamUrl = streamUrl,
                                containerExtension = "m3u8",
                                addedTimestamp = nowMs,
                                groupKey = orderInfo.groupKey,
                                seq = orderInfo.seq,
                                groupOrder = orderInfo.groupOrder,
                                serverOrder = orderInfo.serverOrder,
                                tmdbId = tmdbIdAttr
                            )
                        )
                    }
                    MediaType.MOVIE -> {
                        val orderInfo = movieTracker.nextChannel(rawName)
                        movies.add(
                            MediaItemEntity(
                                id = "vod_$autoStreamId",
                                accountId = accountId,
                                streamId = autoStreamId,
                                name = rawName,
                                title = cleanedQuery.cleanTitle.ifBlank { rawName },
                                type = MediaType.MOVIE.name,
                                posterUrl = logoUrl,
                                backdropUrl = logoUrl,
                                categoryId = catId,
                                categoryName = groupTitle,
                                rating = 0.0,
                                year = cleanedQuery.extractedYear,
                                resolution = detectedQuality,
                                quality = detectedQuality,
                                streamUrl = streamUrl,
                                containerExtension = ext,
                                addedTimestamp = nowMs,
                                groupKey = orderInfo.groupKey,
                                seq = orderInfo.seq,
                                groupOrder = orderInfo.groupOrder,
                                serverOrder = orderInfo.serverOrder,
                                tmdbId = tmdbIdAttr
                            )
                        )
                    }
                    MediaType.SERIES -> {
                        val seriesBaseTitle = cleanedQuery.cleanTitle.ifBlank { rawName }
                        val seriesKey = seriesBaseTitle.lowercase()
                        val (seasonNum, epNum) = extractSeasonAndEpisode(rawName)

                        val accumulator = seriesAccumulators.getOrPut(seriesKey) {
                            val orderInfo = seriesTracker.nextChannel(seriesBaseTitle)
                            val seriesId = autoStreamId
                            SeriesAccumulator(
                                entity = MediaItemEntity(
                                    id = "series_$seriesId",
                                    accountId = accountId,
                                    streamId = seriesId,
                                    name = seriesBaseTitle,
                                    title = seriesBaseTitle,
                                    type = MediaType.SERIES.name,
                                    posterUrl = logoUrl,
                                    backdropUrl = logoUrl,
                                    categoryId = catId,
                                    categoryName = groupTitle,
                                    rating = 0.0,
                                    year = cleanedQuery.extractedYear,
                                    resolution = detectedQuality,
                                    quality = detectedQuality,
                                    streamUrl = streamUrl,
                                    containerExtension = ext,
                                    addedTimestamp = nowMs,
                                    groupKey = orderInfo.groupKey,
                                    seq = orderInfo.seq,
                                    groupOrder = orderInfo.groupOrder,
                                    serverOrder = orderInfo.serverOrder,
                                    tmdbId = tmdbIdAttr
                                )
                            )
                        }

                        val sId = accumulator.entity.streamId
                        val epList = accumulator.episodesBySeason.getOrPut(seasonNum) { mutableListOf() }
                        val effectiveEpNum = if (epNum > 0) epNum else epList.size + 1
                        epList.add(
                            Episode(
                                id = "ep_${sId}_${seasonNum}_$effectiveEpNum",
                                episodeNum = effectiveEpNum,
                                title = "Episode $effectiveEpNum",
                                containerExtension = ext,
                                plot = "",
                                durationSec = 2700,
                                rating = 0.0,
                                coverUrl = logoUrl,
                                streamUrl = streamUrl,
                                seriesId = sId,
                                seasonNumber = seasonNum,
                                quality = detectedQuality
                            )
                        )
                    }
                }

                pendingAttrs = emptyMap()
                pendingTitle = ""
            }
            line = reader.readLine()
        }

        val seriesEntities = mutableListOf<MediaItemEntity>()
        val seriesDetailsBySeriesId = LinkedHashMap<Int, SeriesDetails>()
        for (acc in seriesAccumulators.values) {
            seriesEntities.add(acc.entity)
            val seasons = acc.episodesBySeason.entries
                .sortedBy { it.key }
                .map { (seasonNumber, eps) ->
                    val sortedEps = eps.sortedBy { it.episodeNum }
                    Season(
                        seasonNumber = seasonNumber,
                        name = "Season $seasonNumber",
                        episodeCount = sortedEps.size,
                        episodes = sortedEps
                    )
                }
            seriesDetailsBySeriesId[acc.entity.streamId] = SeriesDetails(
                seriesId = acc.entity.streamId,
                name = acc.entity.name,
                cover = acc.entity.posterUrl,
                backdrop = acc.entity.backdropUrl,
                plot = acc.entity.plot,
                cast = acc.entity.cast,
                director = acc.entity.director,
                genre = acc.entity.genre.ifBlank { acc.entity.categoryName },
                releaseDate = acc.entity.year,
                rating = acc.entity.rating,
                seasons = seasons,
                quality = acc.entity.quality
            )
        }

        return M3uImportResult(
            liveCategories = liveCatMap.values.toList(),
            movieCategories = movieCatMap.values.toList(),
            seriesCategories = seriesCatMap.values.toList(),
            liveStreams = liveStreams,
            movies = movies,
            series = seriesEntities,
            seriesDetailsBySeriesId = seriesDetailsBySeriesId
        )
    }

    private fun extractSeasonAndEpisode(rawName: String): Pair<Int, Int> {
        val match = SERIES_PATTERN_REGEX.find(rawName) ?: return Pair(1, 1)
        val s1 = match.groupValues[1].toIntOrNull()
        val e1 = match.groupValues[2].toIntOrNull()
        if (s1 != null && e1 != null) return Pair(s1.coerceAtLeast(1), e1.coerceAtLeast(1))
        val s2 = match.groupValues[3].toIntOrNull()
        val e2 = match.groupValues[4].toIntOrNull()
        if (s2 != null && e2 != null) return Pair(s2.coerceAtLeast(1), e2.coerceAtLeast(1))
        return Pair(1, 1)
    }

    internal fun classifyEntryType(name: String, groupTitle: String, streamUrl: String): MediaType {
        val lowerUrl = streamUrl.substringBefore('?').lowercase()
        val lowerGroup = groupTitle.lowercase()
        val ext = lowerUrl.substringAfterLast('.', "")

        if (lowerUrl.contains("/series/") || SERIES_PATTERN_REGEX.containsMatchIn(name) || lowerGroup.contains("series")) {
            return MediaType.SERIES
        }
        if (lowerUrl.contains("/movie/") || lowerGroup.contains("movie") || lowerGroup.contains("vod") || ext in VOD_EXTENSIONS) {
            return MediaType.MOVIE
        }
        return MediaType.LIVE
    }
}
