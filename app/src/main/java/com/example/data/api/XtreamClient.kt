package com.example.data.api

import android.util.JsonReader
import android.util.JsonToken
import com.example.data.local.entity.CategoryEntity
import com.example.data.local.entity.MediaItemEntity
import com.example.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStreamReader
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

class XtreamClient {

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
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

    // --- Streamed Category Parsing with JsonReader ---
    suspend fun fetchCategories(
        account: Account,
        type: MediaType
    ): List<CategoryEntity> = withContext(Dispatchers.IO) {
        val action = when (type) {
            MediaType.LIVE -> "get_live_categories"
            MediaType.MOVIE -> "get_vod_categories"
            MediaType.SERIES -> "get_series_categories"
        }
        val defaultName = when (type) {
            MediaType.LIVE -> "Live TV"
            MediaType.MOVIE -> "Movies"
            MediaType.SERIES -> "Series"
        }
        val list = mutableListOf<CategoryEntity>()
        val url = "${normalizeUrl(account.serverUrl)}/player_api.php?username=${account.username}&password=${account.password}&action=$action"
        val request = Request.Builder().url(url).build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val inputStream = response.body?.byteStream() ?: return@withContext emptyList()
                val reader = JsonReader(InputStreamReader(inputStream, "UTF-8"))

                if (reader.peek() == JsonToken.BEGIN_ARRAY) {
                    reader.beginArray()
                    while (reader.hasNext()) {
                        if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                            reader.beginObject()
                            var id = ""
                            var name = defaultName
                            var parentId = 0
                            while (reader.hasNext()) {
                                val field = reader.nextName()
                                if (reader.peek() == JsonToken.NULL) {
                                    reader.nextNull()
                                    continue
                                }
                                when (field) {
                                    "category_id" -> {
                                        id = if (reader.peek() == JsonToken.NUMBER) {
                                            reader.nextInt().toString()
                                        } else {
                                            reader.nextString()
                                        }
                                    }
                                    "category_name" -> name = reader.nextString()
                                    "parent_id" -> {
                                        parentId = if (reader.peek() == JsonToken.NUMBER) {
                                            reader.nextInt()
                                        } else {
                                            reader.nextString().toIntOrNull() ?: 0
                                        }
                                    }
                                    else -> reader.skipValue()
                                }
                            }
                            reader.endObject()

                            if (id.isNotBlank()) {
                                list.add(
                                    CategoryEntity(
                                        id = "${account.id}_${type.name}_$id",
                                        accountId = account.id,
                                        categoryId = id,
                                        categoryName = name,
                                        parentId = parentId,
                                        type = type.name
                                    )
                                )
                            }
                        } else {
                            reader.skipValue()
                        }
                    }
                    reader.endArray()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        list
    }

    // --- Streamed Live Channels Parsing (No giant strings in RAM) ---
    suspend fun streamLiveStreams(
        account: Account,
        batchSize: Int = 500,
        onBatch: suspend (List<MediaItemEntity>) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        val url = "${normalizeUrl(account.serverUrl)}/player_api.php?username=${account.username}&password=${account.password}&action=get_live_streams"
        val request = Request.Builder().url(url).build()
        val base = normalizeUrl(account.serverUrl)
        var totalCount = 0
        val batch = ArrayList<MediaItemEntity>(batchSize)

        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext 0
                val inputStream = response.body?.byteStream() ?: return@withContext 0
                val reader = JsonReader(InputStreamReader(inputStream, "UTF-8"))

                if (reader.peek() == JsonToken.BEGIN_ARRAY) {
                    reader.beginArray()
                    while (reader.hasNext()) {
                        if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                            reader.beginObject()
                            var streamId = 0
                            var name = ""
                            var streamIcon = ""
                            var categoryId = ""

                            while (reader.hasNext()) {
                                val field = reader.nextName()
                                if (reader.peek() == JsonToken.NULL) {
                                    reader.nextNull()
                                    continue
                                }
                                when (field) {
                                    "stream_id" -> {
                                        streamId = if (reader.peek() == JsonToken.NUMBER) {
                                            reader.nextInt()
                                        } else {
                                            reader.nextString().toIntOrNull() ?: 0
                                        }
                                    }
                                    "name" -> name = reader.nextString()
                                    "stream_icon" -> streamIcon = reader.nextString()
                                    "category_id" -> {
                                        categoryId = if (reader.peek() == JsonToken.NUMBER) {
                                            reader.nextInt().toString()
                                        } else {
                                            reader.nextString()
                                        }
                                    }
                                    else -> reader.skipValue()
                                }
                            }
                            reader.endObject()

                            if (streamId > 0) {
                                val channelName = name.ifBlank { "Channel $streamId" }
                                val streamUrl = "$base/live/${account.username}/${account.password}/$streamId.m3u8"
                                batch.add(
                                    MediaItemEntity(
                                        id = "live_$streamId",
                                        accountId = account.id,
                                        streamId = streamId,
                                        name = channelName,
                                        title = channelName,
                                        type = "LIVE",
                                        posterUrl = streamIcon,
                                        backdropUrl = streamIcon,
                                        categoryId = categoryId,
                                        categoryName = "",
                                        rating = 8.0,
                                        year = "2024",
                                        resolution = "FHD",
                                        quality = "Live",
                                        streamUrl = streamUrl,
                                        containerExtension = "m3u8",
                                        addedTimestamp = System.currentTimeMillis()
                                    )
                                )
                                totalCount++

                                if (batch.size >= batchSize) {
                                    onBatch(batch.toList())
                                    batch.clear()
                                    yield() // Free memory and cooperative dispatching
                                }
                            }
                        } else {
                            reader.skipValue()
                        }
                    }
                    reader.endArray()
                }
            }
            if (batch.isNotEmpty()) {
                onBatch(batch.toList())
                batch.clear()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        totalCount
    }

    // --- Streamed Movies Parsing (No giant strings in RAM) ---
    suspend fun streamVodStreams(
        account: Account,
        batchSize: Int = 500,
        onBatch: suspend (List<MediaItemEntity>) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        val url = "${normalizeUrl(account.serverUrl)}/player_api.php?username=${account.username}&password=${account.password}&action=get_vod_streams"
        val request = Request.Builder().url(url).build()
        val base = normalizeUrl(account.serverUrl)
        var totalCount = 0
        val batch = ArrayList<MediaItemEntity>(batchSize)

        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext 0
                val inputStream = response.body?.byteStream() ?: return@withContext 0
                val reader = JsonReader(InputStreamReader(inputStream, "UTF-8"))

                if (reader.peek() == JsonToken.BEGIN_ARRAY) {
                    reader.beginArray()
                    while (reader.hasNext()) {
                        if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                            reader.beginObject()
                            var streamId = 0
                            var name = ""
                            var streamIcon = ""
                            var categoryId = ""
                            var containerExt = "mp4"
                            var rating = 7.0
                            var year = "2024"
                            var addedTime = System.currentTimeMillis()

                            while (reader.hasNext()) {
                                val field = reader.nextName()
                                if (reader.peek() == JsonToken.NULL) {
                                    reader.nextNull()
                                    continue
                                }
                                when (field) {
                                    "stream_id" -> {
                                        streamId = if (reader.peek() == JsonToken.NUMBER) {
                                            reader.nextInt()
                                        } else {
                                            reader.nextString().toIntOrNull() ?: 0
                                        }
                                    }
                                    "name" -> name = reader.nextString()
                                    "stream_icon" -> streamIcon = reader.nextString()
                                    "category_id" -> {
                                        categoryId = if (reader.peek() == JsonToken.NUMBER) {
                                            reader.nextInt().toString()
                                        } else {
                                            reader.nextString()
                                        }
                                    }
                                    "container_extension" -> {
                                        val ext = reader.nextString().trim()
                                        if (ext.isNotBlank()) containerExt = ext
                                    }
                                    "rating" -> {
                                        rating = if (reader.peek() == JsonToken.NUMBER) {
                                            reader.nextDouble()
                                        } else {
                                            reader.nextString().toDoubleOrNull() ?: 7.0
                                        }
                                    }
                                    "year" -> {
                                        year = if (reader.peek() == JsonToken.NUMBER) {
                                            reader.nextInt().toString()
                                        } else {
                                            reader.nextString()
                                        }
                                    }
                                    "added" -> {
                                        addedTime = if (reader.peek() == JsonToken.NUMBER) {
                                            reader.nextLong() * 1000L
                                        } else {
                                            reader.nextString().toLongOrNull()?.let { it * 1000L } ?: System.currentTimeMillis()
                                        }
                                    }
                                    else -> reader.skipValue()
                                }
                            }
                            reader.endObject()

                            if (streamId > 0) {
                                val movieName = name.ifBlank { "Movie $streamId" }
                                val streamUrl = "$base/movie/${account.username}/${account.password}/$streamId.$containerExt"
                                batch.add(
                                    MediaItemEntity(
                                        id = "vod_$streamId",
                                        accountId = account.id,
                                        streamId = streamId,
                                        name = movieName,
                                        title = movieName,
                                        type = "MOVIE",
                                        posterUrl = streamIcon,
                                        backdropUrl = streamIcon,
                                        categoryId = categoryId,
                                        categoryName = "",
                                        rating = rating,
                                        year = year.ifBlank { "2024" },
                                        resolution = if (rating > 8.0) "4K" else "FHD",
                                        quality = if (rating > 8.0) "HDR" else "HD",
                                        streamUrl = streamUrl,
                                        containerExtension = containerExt,
                                        addedTimestamp = addedTime
                                    )
                                )
                                totalCount++

                                if (batch.size >= batchSize) {
                                    onBatch(batch.toList())
                                    batch.clear()
                                    yield() // Free memory and cooperative dispatching
                                }
                            }
                        } else {
                            reader.skipValue()
                        }
                    }
                    reader.endArray()
                }
            }
            if (batch.isNotEmpty()) {
                onBatch(batch.toList())
                batch.clear()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        totalCount
    }

    // --- Streamed Series Parsing (No giant strings in RAM) ---
    suspend fun streamSeries(
        account: Account,
        batchSize: Int = 500,
        onBatch: suspend (List<MediaItemEntity>) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        val url = "${normalizeUrl(account.serverUrl)}/player_api.php?username=${account.username}&password=${account.password}&action=get_series"
        val request = Request.Builder().url(url).build()
        var totalCount = 0
        val batch = ArrayList<MediaItemEntity>(batchSize)

        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext 0
                val inputStream = response.body?.byteStream() ?: return@withContext 0
                val reader = JsonReader(InputStreamReader(inputStream, "UTF-8"))

                if (reader.peek() == JsonToken.BEGIN_ARRAY) {
                    reader.beginArray()
                    while (reader.hasNext()) {
                        if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                            reader.beginObject()
                            var seriesId = 0
                            var name = ""
                            var cover = ""
                            var categoryId = ""
                            var rating = 8.0
                            var year = "2024"

                            while (reader.hasNext()) {
                                val field = reader.nextName()
                                if (reader.peek() == JsonToken.NULL) {
                                    reader.nextNull()
                                    continue
                                }
                                when (field) {
                                    "series_id" -> {
                                        seriesId = if (reader.peek() == JsonToken.NUMBER) {
                                            reader.nextInt()
                                        } else {
                                            reader.nextString().toIntOrNull() ?: 0
                                        }
                                    }
                                    "name" -> name = reader.nextString()
                                    "cover" -> cover = reader.nextString()
                                    "category_id" -> {
                                        categoryId = if (reader.peek() == JsonToken.NUMBER) {
                                            reader.nextInt().toString()
                                        } else {
                                            reader.nextString()
                                        }
                                    }
                                    "rating" -> {
                                        rating = if (reader.peek() == JsonToken.NUMBER) {
                                            reader.nextDouble()
                                        } else {
                                            reader.nextString().toDoubleOrNull() ?: 8.0
                                        }
                                    }
                                    "releaseDate" -> {
                                        val rDate = reader.nextString()
                                        year = if (rDate.length >= 4) rDate.substring(0, 4) else "2024"
                                    }
                                    else -> reader.skipValue()
                                }
                            }
                            reader.endObject()

                            if (seriesId > 0) {
                                val seriesName = name.ifBlank { "Series $seriesId" }
                                batch.add(
                                    MediaItemEntity(
                                        id = "series_$seriesId",
                                        accountId = account.id,
                                        streamId = seriesId,
                                        name = seriesName,
                                        title = seriesName,
                                        type = "SERIES",
                                        posterUrl = cover,
                                        backdropUrl = cover,
                                        categoryId = categoryId,
                                        categoryName = "",
                                        rating = rating,
                                        year = year,
                                        resolution = "4K",
                                        quality = "Dolby Atmos",
                                        streamUrl = "",
                                        containerExtension = "mp4",
                                        addedTimestamp = System.currentTimeMillis()
                                    )
                                )
                                totalCount++

                                if (batch.size >= batchSize) {
                                    onBatch(batch.toList())
                                    batch.clear()
                                    yield() // Free memory and cooperative dispatching
                                }
                            }
                        } else {
                            reader.skipValue()
                        }
                    }
                    reader.endArray()
                }
            }
            if (batch.isNotEmpty()) {
                onBatch(batch.toList())
                batch.clear()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        totalCount
    }

    // --- On-Demand Series Details (Fetched per series, not for the whole catalog) ---
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
