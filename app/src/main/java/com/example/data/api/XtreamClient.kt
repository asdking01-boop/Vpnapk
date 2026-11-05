package com.example.data.api

import android.util.JsonReader
import android.util.JsonToken
import com.example.data.local.ChannelNameNormalizer
import com.example.data.local.QualityDetector
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
        categoryMap: Map<String, String> = emptyMap(),
        onBatch: suspend (List<MediaItemEntity>) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        val url = "${normalizeUrl(account.serverUrl)}/player_api.php?username=${account.username}&password=${account.password}&action=get_live_streams"
        val request = Request.Builder().url(url).build()
        val base = normalizeUrl(account.serverUrl)
        var totalCount = 0
        val batch = ArrayList<MediaItemEntity>(batchSize)
        val groupingTracker = ChannelNameNormalizer.ChannelGroupingTracker()

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
                                val categoryName = categoryMap[categoryId].orEmpty()
                                val detectedQuality = QualityDetector.detectFromNameAndCategory(channelName, categoryName)
                                val streamUrl = "$base/live/${account.username}/${account.password}/$streamId.m3u8"
                                val orderInfo = groupingTracker.nextChannel(channelName)
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
                                        categoryName = categoryName,
                                        rating = 8.0,
                                        year = "2024",
                                        resolution = detectedQuality,
                                        quality = detectedQuality,
                                        streamUrl = streamUrl,
                                        containerExtension = "m3u8",
                                        addedTimestamp = System.currentTimeMillis(),
                                        groupKey = orderInfo.groupKey,
                                        seq = orderInfo.seq,
                                        groupOrder = orderInfo.groupOrder,
                                        serverOrder = orderInfo.serverOrder
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

    companion object {
        /**
         * Parses an Xtream Codes / playlist server addition timestamp (`added`, `date_added`, `last_modified`)
         * into epoch milliseconds. Returns 0L when missing or invalid so the repository can assign/preserve
         * a locally tracked first-seen timestamp (clearly distinguished from a server upload timestamp).
         */
        fun parseServerTimestampMs(raw: String?): Long {
            val cleaned = raw?.trim().orEmpty()
            if (cleaned.isEmpty() || cleaned == "0" || cleaned.equals("null", ignoreCase = true)) {
                return 0L
            }
            val numeric = cleaned.toLongOrNull()
            if (numeric != null) {
                return when {
                    numeric <= 0L -> 0L
                    numeric < 100_000_000_000L -> numeric * 1000L // Unix seconds -> milliseconds
                    else -> numeric // Already epoch milliseconds
                }
            }
            val dateFormats = arrayOf(
                "yyyy-MM-dd HH:mm:ss",
                "yyyy-MM-dd'T'HH:mm:ss",
                "yyyy-MM-dd"
            )
            for (pattern in dateFormats) {
                try {
                    val sdf = java.text.SimpleDateFormat(pattern, java.util.Locale.US).apply {
                        timeZone = java.util.TimeZone.getTimeZone("UTC")
                    }
                    val parsed = sdf.parse(cleaned)?.time ?: 0L
                    if (parsed > 0L) return parsed
                } catch (_: Exception) {
                }
            }
            return 0L
        }

        private val EXPLICIT_YEAR_REGEX = Regex("""\b(19\d{2}|20\d{2})\b""")
        private val TITLE_YEAR_REGEX = Regex("""[\(\[](19\d{2}|20[0-3]\d)[\)\]]""")

        /**
         * Extracts an actual 4-digit release year from Xtream metadata (`year`, `releaseDate`, `release_date`)
         * or from a parenthesized/bracketed year in the title (e.g. "Movie (2024)").
         * Never invents a release year when metadata is missing, and never confuses resolution (e.g. 2160p/1080p)
         * or date_added with release year.
         */
        fun extractReleaseYear(rawYearField: String?, rawTitle: String? = null): String {
            val cleanedField = rawYearField?.trim().orEmpty()
            if (cleanedField.isNotEmpty() && !cleanedField.equals("null", ignoreCase = true) && cleanedField != "0") {
                val match = EXPLICIT_YEAR_REGEX.find(cleanedField)
                if (match != null) {
                    val yr = match.groupValues[1].toIntOrNull() ?: 0
                    if (yr in 1900..2100) return yr.toString()
                }
            }
            val cleanedTitle = rawTitle?.trim().orEmpty()
            if (cleanedTitle.isNotEmpty()) {
                val titleMatch = TITLE_YEAR_REGEX.find(cleanedTitle)
                if (titleMatch != null) {
                    return titleMatch.groupValues[1]
                }
            }
            return ""
        }
    }

    // --- Streamed Movies Parsing (No giant strings in RAM) ---
    suspend fun streamVodStreams(
        account: Account,
        batchSize: Int = 500,
        categoryMap: Map<String, String> = emptyMap(),
        onBatch: suspend (List<MediaItemEntity>) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        val url = "${normalizeUrl(account.serverUrl)}/player_api.php?username=${account.username}&password=${account.password}&action=get_vod_streams"
        val request = Request.Builder().url(url).build()
        val base = normalizeUrl(account.serverUrl)
        var totalCount = 0
        val batch = ArrayList<MediaItemEntity>(batchSize)
        val groupingTracker = ChannelNameNormalizer.ChannelGroupingTracker()

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
                            var rawYear = ""
                            var serverAddedMs = 0L

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
                                    "year", "releaseDate", "release_date" -> {
                                        val candidate = if (reader.peek() == JsonToken.NUMBER) {
                                            reader.nextInt().toString()
                                        } else {
                                            reader.nextString()
                                        }
                                        if (candidate.isNotBlank() && rawYear.isBlank()) {
                                            rawYear = candidate
                                        }
                                    }
                                    "added", "date_added", "last_modified" -> {
                                        val rawAdded = if (reader.peek() == JsonToken.NUMBER) {
                                            reader.nextLong().toString()
                                        } else {
                                            reader.nextString()
                                        }
                                        val parsed = parseServerTimestampMs(rawAdded)
                                        if (parsed > 0L) {
                                            serverAddedMs = parsed
                                        }
                                    }
                                    else -> reader.skipValue()
                                }
                            }
                            reader.endObject()

                            if (streamId > 0) {
                                val movieName = name.ifBlank { "Movie $streamId" }
                                val resolvedYear = extractReleaseYear(rawYear, movieName)
                                val categoryName = categoryMap[categoryId].orEmpty()
                                val detectedQuality = QualityDetector.detectFromNameAndCategory(movieName, categoryName)
                                val streamUrl = "$base/movie/${account.username}/${account.password}/$streamId.$containerExt"
                                val orderInfo = groupingTracker.nextChannel(movieName)
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
                                        categoryName = categoryName,
                                        rating = rating,
                                        year = resolvedYear,
                                        resolution = detectedQuality,
                                        quality = detectedQuality,
                                        streamUrl = streamUrl,
                                        containerExtension = containerExt,
                                        addedTimestamp = serverAddedMs,
                                        groupKey = orderInfo.groupKey,
                                        seq = orderInfo.seq,
                                        groupOrder = orderInfo.groupOrder,
                                        serverOrder = orderInfo.serverOrder
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
        categoryMap: Map<String, String> = emptyMap(),
        onBatch: suspend (List<MediaItemEntity>) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        val url = "${normalizeUrl(account.serverUrl)}/player_api.php?username=${account.username}&password=${account.password}&action=get_series"
        val request = Request.Builder().url(url).build()
        var totalCount = 0
        val batch = ArrayList<MediaItemEntity>(batchSize)
        val groupingTracker = ChannelNameNormalizer.ChannelGroupingTracker()

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
                            var rawYear = ""
                            var serverAddedMs = 0L

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
                                    "releaseDate", "release_date", "year" -> {
                                        val rDate = if (reader.peek() == JsonToken.NUMBER) {
                                            reader.nextInt().toString()
                                        } else {
                                            reader.nextString()
                                        }
                                        if (rDate.isNotBlank() && rawYear.isBlank()) {
                                            rawYear = rDate
                                        }
                                    }
                                    "last_modified", "added", "date_added" -> {
                                        val rawAdded = if (reader.peek() == JsonToken.NUMBER) {
                                            reader.nextLong().toString()
                                        } else {
                                            reader.nextString()
                                        }
                                        val parsed = parseServerTimestampMs(rawAdded)
                                        if (parsed > 0L) {
                                            serverAddedMs = parsed
                                        }
                                    }
                                    else -> reader.skipValue()
                                }
                            }
                            reader.endObject()

                            if (seriesId > 0) {
                                val seriesName = name.ifBlank { "Series $seriesId" }
                                val resolvedYear = extractReleaseYear(rawYear, seriesName)
                                val categoryName = categoryMap[categoryId].orEmpty()
                                val detectedQuality = QualityDetector.detectFromNameAndCategory(seriesName, categoryName)
                                val orderInfo = groupingTracker.nextChannel(seriesName)
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
                                        categoryName = categoryName,
                                        rating = rating,
                                        year = resolvedYear,
                                        resolution = detectedQuality,
                                        quality = detectedQuality,
                                        streamUrl = "",
                                        containerExtension = "mp4",
                                        addedTimestamp = serverAddedMs,
                                        groupKey = orderInfo.groupKey,
                                        seq = orderInfo.seq,
                                        groupOrder = orderInfo.groupOrder,
                                        serverOrder = orderInfo.serverOrder
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

    /**
     * Step 2b: Extracts real video width, height, codec, bitrate, framerate, and audio channels
     * from a JSON "info" object returned by get_vod_info or get_series_info.
     */
    fun parseVideoAudioSpecsFromInfo(infoObj: JSONObject?, fallbackQuality: String = ""): String {
        if (infoObj == null) return fallbackQuality
        return try {
            val videoObj = infoObj.optJSONObject("video")
            val audioObj = infoObj.optJSONObject("audio")

            val width = videoObj?.optInt("width", 0) ?: infoObj.optInt("width", 0)
            val rawHeight = videoObj?.optInt("height", 0) ?: infoObj.optInt("height", 0)
            val effectiveHeight = when {
                rawHeight > 0 -> rawHeight
                width >= 7680 -> 4320
                width >= 3840 -> 2160
                width >= 1920 -> 1080
                width >= 1280 -> 720
                width > 0 -> 480
                else -> 0
            }

            val codecName = videoObj?.optString("codec_name", "")?.ifBlank {
                videoObj.optString("codec_long_name", "")
            } ?: infoObj.optString("video_codec", "")

            val fpsStr = videoObj?.optString("avg_frame_rate", "")?.ifBlank {
                videoObj.optString("r_frame_rate", "")
            } ?: infoObj.optString("framerate", "")
            val fps = QualityDetector.parseFrameRateString(fpsStr)

            val audioChannels = audioObj?.optInt("channels", 0) ?: 0
            val audioLayout = audioObj?.optString("channel_layout", "") ?: ""

            if (effectiveHeight > 0 || codecName.isNotBlank() || fps > 0f || audioChannels > 0) {
                QualityDetector.buildFromRealSpecs(
                    height = effectiveHeight,
                    frameRate = fps,
                    codecOrMime = codecName,
                    audioChannels = audioChannels,
                    audioLayout = audioLayout,
                    fallbackQuality = fallbackQuality
                )
            } else {
                fallbackQuality
            }
        } catch (e: Exception) {
            fallbackQuality
        }
    }

    /**
     * Step 2b: Fetches real video resolution/codec/framerate for a movie from get_vod_info.
     */
    suspend fun getVodVideoInfo(
        account: Account,
        vodId: Int,
        fallbackQuality: String = ""
    ): String? = withContext(Dispatchers.IO) {
        try {
            val url = "${normalizeUrl(account.serverUrl)}/player_api.php?username=${account.username}&password=${account.password}&action=get_vod_info&vod_id=$vodId"
            val request = Request.Builder().url(url).build()
            val body = httpClient.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string().orEmpty() else ""
            }
            if (!body.startsWith("{")) return@withContext null

            val json = try { JSONObject(body) } catch (e: Exception) { return@withContext null }
            val info = json.optJSONObject("info")
            val movieData = json.optJSONObject("movie_data")

            val nameHint = movieData?.optString("name", "") ?: info?.optString("name", "") ?: ""
            val baseFallback = fallbackQuality.ifBlank {
                QualityDetector.detectFromNameAndCategory(nameHint)
            }

            val detected = parseVideoAudioSpecsFromInfo(info, baseFallback)
            detected.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
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

            val seriesBaseQuality = parseVideoAudioSpecsFromInfo(
                info,
                QualityDetector.detectFromNameAndCategory(name, genre)
            )

            val seasonsList = mutableListOf<Season>()
            val keys = seasonsJson.keys()
            val base = normalizeUrl(account.serverUrl)
            var bestEpisodeQuality = seriesBaseQuality

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

                        val epFallback = QualityDetector.detectFromNameAndCategory(epTitle, name, seriesBaseQuality)
                        val epQuality = parseVideoAudioSpecsFromInfo(epInfo, epFallback)
                        if (QualityDetector.rankForBadge(epQuality) > QualityDetector.rankForBadge(bestEpisodeQuality)) {
                            bestEpisodeQuality = epQuality
                        } else if (bestEpisodeQuality.isBlank() && epQuality.isNotBlank()) {
                            bestEpisodeQuality = epQuality
                        }

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
                                seasonNumber = seasonNum,
                                quality = epQuality
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
                seasons = seasonsList.sortedBy { it.seasonNumber },
                quality = bestEpisodeQuality
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
