package com.example.data.api

import com.example.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

class XtreamClient {

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", "KrasTV/2.0 (Android; SmartIPTV; ExoPlayer)")
                .header("Accept", "*/*")
                .build()
            chain.proceed(request)
        }
        .build()

    private fun normalizeUrl(serverUrl: String): String {
        var url = serverUrl.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://$url"
        }
        if (url.endsWith("/")) {
            url = url.substring(0, url.length - 1)
        }
        return url
    }

    suspend fun authenticate(
        serverUrl: String,
        user: String,
        pass: String
    ): Result<Account> = withContext(Dispatchers.IO) {
        try {
            val base = normalizeUrl(serverUrl)
            val apiUrl = "$base/player_api.php?username=$user&password=$pass"
            val request = Request.Builder().url(apiUrl).get().build()

            val body = httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        Exception("Server returned HTTP error ${response.code}. Please verify the URL.")
                    )
                }
                response.body?.string().orEmpty()
            }

            if (body.isBlank() || body == "[]" || body == "null") {
                return@withContext Result.failure(
                    Exception("Server returned an empty response. Please check server status.")
                )
            }

            val json = try {
                JSONObject(body)
            } catch (e: Exception) {
                return@withContext Result.failure(
                    Exception("Server response is not valid JSON. Please check server address.")
                )
            }

            if (!json.has("user_info")) {
                return@withContext Result.failure(
                    Exception("Unrecognized Xtream Codes response. Please verify credentials.")
                )
            }

            val userInfo = json.optJSONObject("user_info") ?: JSONObject()
            val authStatus = userInfo.optString("auth", "1")
            val status = userInfo.optString("status", "Active")

            if (authStatus == "0" || status.equals("Disabled", ignoreCase = true) || status.equals("Banned", ignoreCase = true)) {
                return@withContext Result.failure(
                    Exception("Account authorization failed: $status")
                )
            }

            val expDateRaw = userInfo.optString("exp_date", "")
            val maxConn = userInfo.optString("max_connections", "1")

            val account = Account(
                id = java.util.UUID.randomUUID().toString(),
                name = user,
                serverUrl = base,
                username = user,
                password = pass,
                isActive = true,
                status = status,
                expDate = expDateRaw,
                maxConnections = maxConn
            )

            Result.success(account)
        } catch (e: UnknownHostException) {
            Result.failure(Exception("Cannot resolve host. Please check your internet connection or URL."))
        } catch (e: SocketTimeoutException) {
            Result.failure(Exception("Server connection timed out. Server might be down."))
        } catch (e: Exception) {
            Result.failure(Exception(e.message ?: "Connection error occurred"))
        }
    }

    suspend fun getLiveCategories(account: Account): List<Category> = withContext(Dispatchers.IO) {
        val list = mutableListOf<Category>()
        try {
            val url = "${normalizeUrl(account.serverUrl)}/player_api.php?username=${account.username}&password=${account.password}&action=get_live_categories"
            val request = Request.Builder().url(url).build()
            val body = httpClient.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string().orEmpty() else ""
            }

            if (body.startsWith("[")) {
                val array = JSONArray(body)
                for (i in 0 until array.length()) {
                    try {
                        val obj = array.optJSONObject(i) ?: continue
                        val id = obj.optString("category_id")
                        val name = obj.optString("category_name", "Unknown")
                        if (id.isNotBlank()) {
                            list.add(
                                Category(
                                    categoryId = id,
                                    categoryName = name,
                                    parentId = obj.optInt("parent_id", 0),
                                    type = MediaType.LIVE
                                )
                            )
                        }
                    } catch (e: Exception) {
                        // Skip individual malformed item
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        list
    }

    suspend fun getLiveStreams(account: Account, categoryId: String? = null): List<MediaItem> = withContext(Dispatchers.IO) {
        val list = mutableListOf<MediaItem>()
        try {
            val catParam = if (!categoryId.isNullOrEmpty()) "&category_id=$categoryId" else ""
            val url = "${normalizeUrl(account.serverUrl)}/player_api.php?username=${account.username}&password=${account.password}&action=get_live_streams$catParam"
            val request = Request.Builder().url(url).build()
            val body = httpClient.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string().orEmpty() else ""
            }

            if (body.startsWith("[")) {
                val array = JSONArray(body)
                val base = normalizeUrl(account.serverUrl)
                for (i in 0 until array.length()) {
                    try {
                        val obj = array.optJSONObject(i) ?: continue
                        val sId = obj.optInt("stream_id", 0)
                        if (sId <= 0) continue
                        val name = obj.optString("name", "Channel $sId")
                        val icon = obj.optString("stream_icon", "")
                        val catId = obj.optString("category_id", "")
                        val streamUrl = "$base/live/${account.username}/${account.password}/$sId.m3u8"

                        list.add(
                            MediaItem(
                                id = "live_$sId",
                                streamId = sId,
                                name = name,
                                title = name,
                                type = MediaType.LIVE,
                                streamIcon = icon,
                                backdrop = icon,
                                categoryId = catId,
                                resolution = "FHD",
                                quality = "Live",
                                streamUrl = streamUrl,
                                containerExtension = "m3u8"
                            )
                        )
                    } catch (e: Exception) {
                        // Safe skip
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        list
    }

    suspend fun getVodCategories(account: Account): List<Category> = withContext(Dispatchers.IO) {
        val list = mutableListOf<Category>()
        try {
            val url = "${normalizeUrl(account.serverUrl)}/player_api.php?username=${account.username}&password=${account.password}&action=get_vod_categories"
            val request = Request.Builder().url(url).build()
            val body = httpClient.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string().orEmpty() else ""
            }

            if (body.startsWith("[")) {
                val array = JSONArray(body)
                for (i in 0 until array.length()) {
                    try {
                        val obj = array.optJSONObject(i) ?: continue
                        val id = obj.optString("category_id")
                        val name = obj.optString("category_name", "Movies")
                        if (id.isNotBlank()) {
                            list.add(
                                Category(
                                    categoryId = id,
                                    categoryName = name,
                                    parentId = obj.optInt("parent_id", 0),
                                    type = MediaType.MOVIE
                                )
                            )
                        }
                    } catch (e: Exception) {
                        // Safe skip
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        list
    }

    suspend fun getVodStreams(account: Account, categoryId: String? = null): List<MediaItem> = withContext(Dispatchers.IO) {
        val list = mutableListOf<MediaItem>()
        try {
            val catParam = if (!categoryId.isNullOrEmpty()) "&category_id=$categoryId" else ""
            val url = "${normalizeUrl(account.serverUrl)}/player_api.php?username=${account.username}&password=${account.password}&action=get_vod_streams$catParam"
            val request = Request.Builder().url(url).build()
            val body = httpClient.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string().orEmpty() else ""
            }

            if (body.startsWith("[")) {
                val array = JSONArray(body)
                val base = normalizeUrl(account.serverUrl)
                for (i in 0 until array.length()) {
                    try {
                        val obj = array.optJSONObject(i) ?: continue
                        val sId = obj.optInt("stream_id", 0)
                        if (sId <= 0) continue
                        val name = obj.optString("name", "Movie $sId")
                        val icon = obj.optString("stream_icon", "")
                        val catId = obj.optString("category_id", "")
                        val ext = obj.optString("container_extension", "mp4").ifBlank { "mp4" }
                        val ratingStr = obj.optString("rating", "7.5")
                        val rating = ratingStr.toDoubleOrNull() ?: 7.0
                        val year = obj.optString("year", "2024")

                        val streamUrl = "$base/movie/${account.username}/${account.password}/$sId.$ext"

                        list.add(
                            MediaItem(
                                id = "vod_$sId",
                                streamId = sId,
                                name = name,
                                title = name,
                                type = MediaType.MOVIE,
                                streamIcon = icon,
                                backdrop = icon,
                                categoryId = catId,
                                rating = rating,
                                year = year,
                                resolution = if (rating > 8.0) "4K" else "FHD",
                                quality = if (rating > 8.0) "HDR" else "HD",
                                streamUrl = streamUrl,
                                containerExtension = ext
                            )
                        )
                    } catch (e: Exception) {
                        // Safe skip
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        list
    }

    suspend fun getSeriesCategories(account: Account): List<Category> = withContext(Dispatchers.IO) {
        val list = mutableListOf<Category>()
        try {
            val url = "${normalizeUrl(account.serverUrl)}/player_api.php?username=${account.username}&password=${account.password}&action=get_series_categories"
            val request = Request.Builder().url(url).build()
            val body = httpClient.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string().orEmpty() else ""
            }

            if (body.startsWith("[")) {
                val array = JSONArray(body)
                for (i in 0 until array.length()) {
                    try {
                        val obj = array.optJSONObject(i) ?: continue
                        val id = obj.optString("category_id")
                        val name = obj.optString("category_name", "Series")
                        if (id.isNotBlank()) {
                            list.add(
                                Category(
                                    categoryId = id,
                                    categoryName = name,
                                    parentId = obj.optInt("parent_id", 0),
                                    type = MediaType.SERIES
                                )
                            )
                        }
                    } catch (e: Exception) {
                        // Safe skip
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        list
    }

    suspend fun getSeries(account: Account, categoryId: String? = null): List<MediaItem> = withContext(Dispatchers.IO) {
        val list = mutableListOf<MediaItem>()
        try {
            val catParam = if (!categoryId.isNullOrEmpty()) "&category_id=$categoryId" else ""
            val url = "${normalizeUrl(account.serverUrl)}/player_api.php?username=${account.username}&password=${account.password}&action=get_series$catParam"
            val request = Request.Builder().url(url).build()
            val body = httpClient.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string().orEmpty() else ""
            }

            if (body.startsWith("[")) {
                val array = JSONArray(body)
                for (i in 0 until array.length()) {
                    try {
                        val obj = array.optJSONObject(i) ?: continue
                        val sId = obj.optInt("series_id", 0)
                        if (sId <= 0) continue
                        val name = obj.optString("name", "Series $sId")
                        val cover = obj.optString("cover", "")
                        val plot = obj.optString("plot", "")
                        val catId = obj.optString("category_id", "")
                        val ratingStr = obj.optString("rating", "8.0")
                        val rating = ratingStr.toDoubleOrNull() ?: 8.0
                        val releaseDate = obj.optString("releaseDate", "2024")
                        val year = if (releaseDate.length >= 4) releaseDate.substring(0, 4) else "2024"

                        list.add(
                            MediaItem(
                                id = "series_$sId",
                                streamId = sId,
                                name = name,
                                title = name,
                                type = MediaType.SERIES,
                                streamIcon = cover,
                                backdrop = cover,
                                categoryId = catId,
                                plot = plot,
                                rating = rating,
                                year = year,
                                resolution = "4K",
                                quality = "Dolby Atmos"
                            )
                        )
                    } catch (e: Exception) {
                        // Safe skip
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        list
    }

    suspend fun getSeriesDetails(account: Account, seriesId: Int): SeriesDetails? = withContext(Dispatchers.IO) {
        try {
            val url = "${normalizeUrl(account.serverUrl)}/player_api.php?username=${account.username}&password=${account.password}&action=get_series_info&series_id=$seriesId"
            val request = Request.Builder().url(url).build()
            val body = httpClient.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string().orEmpty() else ""
            }
            if (!body.startsWith("{")) return@withContext null

            val json = try { JSONObject(body) } catch (e: Exception) { return@withContext null }
            val info = json.optJSONObject("info") ?: JSONObject()
            val seasonsJson = json.optJSONObject("episodes") ?: JSONObject()

            val name = info.optString("name", "Series $seriesId")
            val cover = info.optString("cover", "")
            val plot = info.optString("plot", "")
            val cast = info.optString("cast", "")
            val director = info.optString("director", "")
            val genre = info.optString("genre", "")
            val releaseDate = info.optString("releaseDate", "")
            val rating = info.optDouble("rating", 8.2)

            val seasonsList = mutableListOf<Season>()
            val keys = seasonsJson.keys()
            val base = normalizeUrl(account.serverUrl)

            while (keys.hasNext()) {
                val seasonNumStr = keys.next()
                val seasonNum = seasonNumStr.toIntOrNull() ?: 1
                val epArray = seasonsJson.optJSONArray(seasonNumStr) ?: JSONArray()

                val epList = mutableListOf<Episode>()
                for (j in 0 until epArray.length()) {
                    try {
                        val epObj = epArray.optJSONObject(j) ?: continue
                        val epId = epObj.optString("id", "${seriesId}_${seasonNum}_${j + 1}")
                        val epNum = epObj.optInt("episode_num", j + 1)
                        val epTitle = epObj.optString("title", "Episode $epNum")
                        val ext = epObj.optString("container_extension", "mp4").ifBlank { "mp4" }
                        val epInfo = epObj.optJSONObject("info")
                        val epPlot = epInfo?.optString("plot", "") ?: ""
                        val duration = epInfo?.optInt("duration_secs", 2700) ?: 2700

                        val streamUrl = "$base/series/${account.username}/${account.password}/$epId.$ext"

                        epList.add(
                            Episode(
                                id = epId,
                                episodeNum = epNum,
                                title = epTitle,
                                containerExtension = ext,
                                plot = epPlot,
                                durationSec = duration,
                                rating = 8.0,
                                streamUrl = streamUrl,
                                seriesId = seriesId,
                                seasonNumber = seasonNum
                            )
                        )
                    } catch (e: Exception) {
                        // Safe skip
                    }
                }

                seasonsList.add(
                    Season(
                        seasonNumber = seasonNum,
                        name = "Season $seasonNum",
                        episodeCount = epList.size,
                        episodes = epList
                    )
                )
            }

            SeriesDetails(
                seriesId = seriesId,
                name = name,
                cover = cover,
                plot = plot,
                cast = cast,
                director = director,
                genre = genre,
                releaseDate = releaseDate,
                rating = rating,
                seasons = seasonsList.sortedBy { it.seasonNumber }
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
