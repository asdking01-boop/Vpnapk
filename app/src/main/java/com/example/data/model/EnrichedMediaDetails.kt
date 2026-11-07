package com.example.data.model

data class CastMember(
    val id: Int,
    val name: String,
    val character: String = "",
    val profileUrl: String = ""
)

data class SimilarMedia(
    val tmdbId: Int,
    val title: String,
    val posterUrl: String = "",
    val backdropUrl: String = "",
    val rating: Double = 0.0,
    val releaseYear: String = "",
    val overview: String = "",
    val isSeries: Boolean = false
)

data class EnrichedMediaDetails(
    val mediaId: String,
    val streamId: Int,
    val tmdbId: Int = 0,
    val title: String,
    val type: MediaType,
    val posterUrl: String = "",
    val backdropUrl: String = "",
    val plot: String = "",
    val year: String = "",
    val releaseDate: String = "",
    val duration: String = "",
    val rating: Double = 0.0,
    val genres: List<String> = emptyList(),
    val director: String = "",
    val cast: List<CastMember> = emptyList(),
    val youtubeTrailer: String = "",
    val quality: String = "",
    val seasons: List<Season> = emptyList(),
    val similar: List<SimilarMedia> = emptyList(),
    val userRating: Double = 0.0,
    val isFavorite: Boolean = false,
    val isInWatchlist: Boolean = false,
    val cachedTimestampMs: Long = System.currentTimeMillis()
) {
    fun toJson(): String {
        val root = org.json.JSONObject()
        root.put("mediaId", mediaId)
        root.put("streamId", streamId)
        root.put("tmdbId", tmdbId)
        root.put("title", title)
        root.put("type", type.name)
        root.put("posterUrl", posterUrl)
        root.put("backdropUrl", backdropUrl)
        root.put("plot", plot)
        root.put("year", year)
        root.put("releaseDate", releaseDate)
        root.put("duration", duration)
        root.put("rating", rating)
        root.put("director", director)
        root.put("youtubeTrailer", youtubeTrailer)
        root.put("quality", quality)
        root.put("userRating", userRating)
        root.put("isFavorite", isFavorite)
        root.put("isInWatchlist", isInWatchlist)
        root.put("cachedTimestampMs", cachedTimestampMs)

        val genresArr = org.json.JSONArray()
        for (g in genres) genresArr.put(g)
        root.put("genres", genresArr)

        val castArr = org.json.JSONArray()
        for (c in cast) {
            val cObj = org.json.JSONObject()
            cObj.put("id", c.id)
            cObj.put("name", c.name)
            cObj.put("character", c.character)
            cObj.put("profileUrl", c.profileUrl)
            castArr.put(cObj)
        }
        root.put("cast", castArr)

        val similarArr = org.json.JSONArray()
        for (s in similar) {
            val sObj = org.json.JSONObject()
            sObj.put("tmdbId", s.tmdbId)
            sObj.put("title", s.title)
            sObj.put("posterUrl", s.posterUrl)
            sObj.put("backdropUrl", s.backdropUrl)
            sObj.put("rating", s.rating)
            sObj.put("releaseYear", s.releaseYear)
            sObj.put("overview", s.overview)
            sObj.put("isSeries", s.isSeries)
            similarArr.put(sObj)
        }
        root.put("similar", similarArr)

        val seasonsArr = org.json.JSONArray()
        for (sn in seasons) {
            val snObj = org.json.JSONObject()
            snObj.put("seasonNumber", sn.seasonNumber)
            snObj.put("name", sn.name)
            snObj.put("episodeCount", sn.episodeCount)

            val epArr = org.json.JSONArray()
            for (ep in sn.episodes) {
                val epObj = org.json.JSONObject()
                epObj.put("id", ep.id)
                epObj.put("episodeNum", ep.episodeNum)
                epObj.put("title", ep.title)
                epObj.put("containerExtension", ep.containerExtension)
                epObj.put("plot", ep.plot)
                epObj.put("durationSec", ep.durationSec)
                epObj.put("rating", ep.rating)
                epObj.put("coverUrl", ep.coverUrl)
                epObj.put("streamUrl", ep.streamUrl)
                epObj.put("seriesId", ep.seriesId)
                epObj.put("seasonNumber", ep.seasonNumber)
                epObj.put("quality", ep.quality)
                epArr.put(epObj)
            }
            snObj.put("episodes", epArr)
            seasonsArr.put(snObj)
        }
        root.put("seasons", seasonsArr)

        return root.toString()
    }

    companion object {
        fun fromJson(jsonStr: String): EnrichedMediaDetails? {
            if (jsonStr.isBlank()) return null
            return try {
                val root = org.json.JSONObject(jsonStr)
                val mediaId = root.optString("mediaId", "")
                val streamId = root.optInt("streamId", 0)
                val tmdbId = root.optInt("tmdbId", 0)
                val title = root.optString("title", "")
                val typeStr = root.optString("type", "MOVIE")
                val type = try { MediaType.valueOf(typeStr) } catch (_: Exception) { MediaType.MOVIE }
                val posterUrl = root.optString("posterUrl", "")
                val backdropUrl = root.optString("backdropUrl", "")
                val plot = root.optString("plot", "")
                val year = root.optString("year", "")
                val releaseDate = root.optString("releaseDate", "")
                val duration = root.optString("duration", "")
                val rating = root.optDouble("rating", 0.0)
                val director = root.optString("director", "")
                val youtubeTrailer = root.optString("youtubeTrailer", "")
                val quality = root.optString("quality", "")
                val userRating = root.optDouble("userRating", 0.0)
                val isFavorite = root.optBoolean("isFavorite", false)
                val isInWatchlist = root.optBoolean("isInWatchlist", false)
                val cachedTimestampMs = root.optLong("cachedTimestampMs", System.currentTimeMillis())

                val genresList = mutableListOf<String>()
                val genresArr = root.optJSONArray("genres")
                if (genresArr != null) {
                    for (i in 0 until genresArr.length()) {
                        val g = genresArr.optString(i, "").trim()
                        if (g.isNotEmpty()) genresList.add(g)
                    }
                }

                val castList = mutableListOf<CastMember>()
                val castArr = root.optJSONArray("cast")
                if (castArr != null) {
                    for (i in 0 until castArr.length()) {
                        val cObj = castArr.optJSONObject(i) ?: continue
                        castList.add(
                            CastMember(
                                id = cObj.optInt("id", 0),
                                name = cObj.optString("name", ""),
                                character = cObj.optString("character", ""),
                                profileUrl = cObj.optString("profileUrl", "")
                            )
                        )
                    }
                }

                val similarList = mutableListOf<SimilarMedia>()
                val similarArr = root.optJSONArray("similar")
                if (similarArr != null) {
                    for (i in 0 until similarArr.length()) {
                        val sObj = similarArr.optJSONObject(i) ?: continue
                        similarList.add(
                            SimilarMedia(
                                tmdbId = sObj.optInt("tmdbId", 0),
                                title = sObj.optString("title", ""),
                                posterUrl = sObj.optString("posterUrl", ""),
                                backdropUrl = sObj.optString("backdropUrl", ""),
                                rating = sObj.optDouble("rating", 0.0),
                                releaseYear = sObj.optString("releaseYear", ""),
                                overview = sObj.optString("overview", ""),
                                isSeries = sObj.optBoolean("isSeries", false)
                            )
                        )
                    }
                }

                val seasonsList = mutableListOf<Season>()
                val seasonsArr = root.optJSONArray("seasons")
                if (seasonsArr != null) {
                    for (i in 0 until seasonsArr.length()) {
                        val snObj = seasonsArr.optJSONObject(i) ?: continue
                        val snNum = snObj.optInt("seasonNumber", i + 1)
                        val snName = snObj.optString("name", "Season $snNum")
                        val epList = mutableListOf<Episode>()
                        val epArr = snObj.optJSONArray("episodes")
                        if (epArr != null) {
                            for (j in 0 until epArr.length()) {
                                val epObj = epArr.optJSONObject(j) ?: continue
                                epList.add(
                                    Episode(
                                        id = epObj.optString("id", "${streamId}_${snNum}_${j + 1}"),
                                        episodeNum = epObj.optInt("episodeNum", j + 1),
                                        title = epObj.optString("title", "Episode ${j + 1}"),
                                        containerExtension = epObj.optString("containerExtension", "mp4"),
                                        plot = epObj.optString("plot", ""),
                                        durationSec = epObj.optInt("durationSec", 0),
                                        rating = epObj.optDouble("rating", 0.0),
                                        coverUrl = epObj.optString("coverUrl", ""),
                                        streamUrl = epObj.optString("streamUrl", ""),
                                        seriesId = epObj.optInt("seriesId", streamId),
                                        seasonNumber = epObj.optInt("seasonNumber", snNum),
                                        quality = epObj.optString("quality", "")
                                    )
                                )
                            }
                        }
                        seasonsList.add(
                            Season(
                                seasonNumber = snNum,
                                name = snName,
                                episodeCount = snObj.optInt("episodeCount", epList.size),
                                episodes = epList
                            )
                        )
                    }
                }

                EnrichedMediaDetails(
                    mediaId = mediaId,
                    streamId = streamId,
                    tmdbId = tmdbId,
                    title = title,
                    type = type,
                    posterUrl = posterUrl,
                    backdropUrl = backdropUrl,
                    plot = plot,
                    year = year,
                    releaseDate = releaseDate,
                    duration = duration,
                    rating = rating,
                    genres = genresList,
                    director = director,
                    cast = castList,
                    youtubeTrailer = youtubeTrailer,
                    quality = quality,
                    seasons = seasonsList,
                    similar = similarList,
                    userRating = userRating,
                    isFavorite = isFavorite,
                    isInWatchlist = isInWatchlist,
                    cachedTimestampMs = cachedTimestampMs
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
