package com.example

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.api.XtreamClient
import com.example.data.local.KrasRepository
import com.example.data.model.Account
import com.example.data.model.MediaType
import com.example.data.model.UserSettings
import com.example.sync.SmartSyncEngine
import com.example.sync.SmartSyncStore
import com.example.sync.SyncContentType
import com.example.sync.SyncDeviceConstraints
import com.example.sync.SyncOutcome
import com.example.sync.SyncTriggerReason
import com.example.viewmodel.KrasViewModel
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SmartSyncEngineRobolectricTest {

    private lateinit var context: Context
    private lateinit var repository: KrasRepository
    private lateinit var store: SmartSyncStore

    private var fakeClockMs: Long = 1_750_000_000_000L
    private var fakeConstraints = SyncDeviceConstraints(
        isNetworkAvailable = true,
        isWifiConnected = true,
        isBatteryLowOrPowerSave = false
    )

    private val recordedRequests = CopyOnWriteArrayList<Request>()
    private var responseHandler: (Request) -> Response = { req -> buildDefaultXtreamResponse(req) }

    private fun testAccount(
        id: String = "acc_smart_1",
        username: String = "user1",
        password: String = "pass1",
        serverUrl: String = "http://iptv.local:8080"
    ) = Account(
        id = id,
        name = "Smart Account $id",
        serverUrl = serverUrl,
        username = username,
        password = password,
        isActive = true,
        isDemo = false
    )

    private fun buildTestEngine(): SmartSyncEngine {
        val mockHttpClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                recordedRequests.add(req)
                responseHandler(req)
            })
            .build()

        val client = XtreamClient(httpClient = mockHttpClient)
        return SmartSyncEngine(
            context = context,
            repository = repository,
            store = store,
            xtreamClient = client,
            clockMs = { fakeClockMs },
            deviceConstraintsProvider = { fakeConstraints }
        )
    }

    private fun jsonResponse(
        req: Request,
        code: Int = 200,
        body: String = "[]",
        etag: String? = null,
        lastModified: String? = null
    ): Response {
        val builder = Response.Builder()
            .request(req)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 304) "Not Modified" else if (code == 200) "OK" else "Error")
            .body(body.toResponseBody("application/json".toMediaType()))
        if (!etag.isNullOrBlank()) builder.header("ETag", etag)
        if (!lastModified.isNullOrBlank()) builder.header("Last-Modified", lastModified)
        return builder.build()
    }

    private fun buildDefaultXtreamResponse(
        req: Request,
        etagSuffix: String = "v1",
        moviesJson: String = DEFAULT_MOVIES_JSON,
        seriesJson: String = DEFAULT_SERIES_JSON,
        liveJson: String = DEFAULT_LIVE_JSON,
        support304: Boolean = false
    ): Response {
        val url = req.url.toString()
        val action = req.url.queryParameter("action")
        val etag = "\"${action ?: "auth"}_$etagSuffix\""
        val lastMod = "Wed, 21 Oct 2026 07:28:00 GMT"

        if (support304 && req.header("If-None-Match") == etag) {
            return jsonResponse(req, code = 304, body = "", etag = etag, lastModified = lastMod)
        }

        return when (action) {
            null -> jsonResponse(
                req,
                body = """{"user_info":{"status":"Active","exp_date":"1893456000","max_connections":"2","active_cons":"1"}}""",
                etag = etag,
                lastModified = lastMod
            )
            "get_vod_categories" -> jsonResponse(
                req,
                body = """[{"category_id":"10","category_name":"Action"}]""",
                etag = etag,
                lastModified = lastMod
            )
            "get_series_categories" -> jsonResponse(
                req,
                body = """[{"category_id":"20","category_name":"Drama"}]""",
                etag = etag,
                lastModified = lastMod
            )
            "get_live_categories" -> jsonResponse(
                req,
                body = """[{"category_id":"30","category_name":"Sports"}]""",
                etag = etag,
                lastModified = lastMod
            )
            "get_vod_streams" -> jsonResponse(req, body = moviesJson, etag = etag, lastModified = lastMod)
            "get_series" -> jsonResponse(req, body = seriesJson, etag = etag, lastModified = lastMod)
            "get_live_streams" -> jsonResponse(req, body = liveJson, etag = etag, lastModified = lastMod)
            "get_short_epg" -> jsonResponse(
                req,
                // "Championship Live" base64 = Q2hhbXBpb25zaGlwIExpdmU=
                body = """{"epg_listings":[{"title":"Q2hhbXBpb25zaGlwIExpdmU="}]}""",
                etag = etag,
                lastModified = lastMod
            )
            else -> jsonResponse(req, body = "[]")
        }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        repository = KrasRepository(context)
        store = SmartSyncStore(context)
        fakeClockMs = 1_750_000_000_000L
        fakeConstraints = SyncDeviceConstraints(
            isNetworkAvailable = true,
            isWifiConnected = true,
            isBatteryLowOrPowerSave = false
        )
        recordedRequests.clear()
        responseHandler = { req -> buildDefaultXtreamResponse(req) }
        store.clearAccount("acc_smart_1")
        store.clearAccount("acc_smart_2")
        runBlocking {
            repository.clearOldRows("acc_smart_1", "MOVIE")
            repository.clearOldRows("acc_smart_1", "SERIES")
            repository.clearOldRows("acc_smart_1", "LIVE")
            repository.clearOldRows("acc_smart_2", "MOVIE")
            repository.clearOldRows("acc_smart_2", "SERIES")
            repository.clearOldRows("acc_smart_2", "LIVE")
        }
    }

    @Test
    fun `repeated app launches perform zero redundant network requests and stay silent`() = runBlocking {
        val account = testAccount("acc_smart_1")
        repository.addAccount(account)
        val engine = buildTestEngine()
        val settings = UserSettings()
        val manualMessages = mutableListOf<String?>()

        // Launch 1: Empty Room -> initial sync runs silently
        val launch1 = engine.syncAccount(
            account = account,
            requestedTypes = SyncContentType.entries.toList(),
            triggerReason = SyncTriggerReason.APP_STARTUP,
            settings = settings,
            onManualProgress = { manualMessages.add(it) },
            onTmdbMetadataSync = { true },
            onFavoritesHistorySync = { true }
        )

        assertTrue("First launch should fetch initial catalog", launch1.totalNetworkRequests > 0)
        assertEquals(SyncOutcome.SUCCESS, launch1.outcomes[SyncContentType.MOVIES])
        assertEquals(SyncOutcome.SUCCESS, launch1.outcomes[SyncContentType.SERIES])
        assertEquals(SyncOutcome.SUCCESS, launch1.outcomes[SyncContentType.LIVE_TV])
        assertEquals(SyncOutcome.SUCCESS, launch1.outcomes[SyncContentType.CATEGORIES])
        assertEquals(SyncOutcome.SUCCESS, launch1.outcomes[SyncContentType.EPG])
        assertEquals(SyncOutcome.SUCCESS, launch1.outcomes[SyncContentType.ACCOUNT_STATUS])
        // Automatic launch must NEVER emit manual progress popups
        assertTrue("Automatic sync must emit zero manual progress callbacks", manualMessages.isEmpty())
        assertFalse(engine.uiState.value.isManualSyncRunning)
        assertNull(engine.uiState.value.activeManualMessage)

        // Launch 2 (10 minutes later): Everything is fresh -> ZERO HTTP requests
        recordedRequests.clear()
        fakeClockMs += 10 * 60_000L
        val launch2 = engine.syncAccount(
            account = account,
            requestedTypes = SyncContentType.entries.toList(),
            triggerReason = SyncTriggerReason.APP_STARTUP,
            settings = settings,
            onManualProgress = { manualMessages.add(it) },
            onTmdbMetadataSync = { true },
            onFavoritesHistorySync = { true }
        )
        assertEquals(0, launch2.totalNetworkRequests)
        assertEquals(0, recordedRequests.size)
        assertEquals(SyncOutcome.SKIPPED_FRESH, launch2.outcomes[SyncContentType.MOVIES])
        assertEquals(SyncOutcome.SKIPPED_FRESH, launch2.outcomes[SyncContentType.SERIES])
        assertEquals(SyncOutcome.SKIPPED_FRESH, launch2.outcomes[SyncContentType.LIVE_TV])
        assertEquals(SyncOutcome.SKIPPED_FRESH, launch2.outcomes[SyncContentType.CATEGORIES])
        assertEquals(SyncOutcome.SKIPPED_FRESH, launch2.outcomes[SyncContentType.EPG])
        assertEquals(SyncOutcome.SKIPPED_FRESH, launch2.outcomes[SyncContentType.ACCOUNT_STATUS])
        assertEquals(SyncOutcome.SKIPPED_UNCHANGED, launch2.outcomes[SyncContentType.FAVORITES_HISTORY])

        // Launch 3 (1 hour later): Still fresh -> ZERO HTTP requests
        fakeClockMs += 60 * 60_000L
        val launch3 = engine.syncAccount(
            account = account,
            requestedTypes = SyncContentType.entries.toList(),
            triggerReason = SyncTriggerReason.APP_STARTUP,
            settings = settings
        )
        assertEquals(0, launch3.totalNetworkRequests)
        assertEquals(0, recordedRequests.size)
    }

    @Test
    fun `default refresh intervals selectively sync only stale content types`() = runBlocking {
        val account = testAccount("acc_smart_1")
        repository.addAccount(account)
        val engine = buildTestEngine()
        val settings = UserSettings() // Defaults: EPG=3h, Movies=6h, Series=6h, Live=12h, Cat=24h, TMDb=24h, Account=24h
        val tmdbSyncCalls = AtomicInteger(0)

        // Initial seed
        engine.syncAccount(
            account = account,
            requestedTypes = SyncContentType.entries.toList(),
            triggerReason = SyncTriggerReason.APP_STARTUP,
            settings = settings,
            onTmdbMetadataSync = {
                tmdbSyncCalls.incrementAndGet()
                true
            }
        )
        assertEquals(1, tmdbSyncCalls.get())

        // +3.5 hours: ONLY EPG (3h default) should sync
        recordedRequests.clear()
        fakeClockMs += (3.5 * 3_600_000L).toLong()
        val at3_5h = engine.syncAccount(
            account = account,
            requestedTypes = SyncContentType.entries.toList(),
            triggerReason = SyncTriggerReason.PERIODIC_BACKGROUND,
            settings = settings,
            onTmdbMetadataSync = {
                tmdbSyncCalls.incrementAndGet()
                true
            }
        )
        assertEquals(SyncOutcome.SUCCESS, at3_5h.outcomes[SyncContentType.EPG])
        assertEquals(SyncOutcome.SKIPPED_FRESH, at3_5h.outcomes[SyncContentType.MOVIES])
        assertEquals(SyncOutcome.SKIPPED_FRESH, at3_5h.outcomes[SyncContentType.SERIES])
        assertEquals(SyncOutcome.SKIPPED_FRESH, at3_5h.outcomes[SyncContentType.LIVE_TV])
        assertEquals(SyncOutcome.SKIPPED_FRESH, at3_5h.outcomes[SyncContentType.CATEGORIES])
        assertEquals(SyncOutcome.SKIPPED_FRESH, at3_5h.outcomes[SyncContentType.ACCOUNT_STATUS])
        assertEquals(SyncOutcome.SKIPPED_FRESH, at3_5h.outcomes[SyncContentType.TMDB_METADATA])
        assertTrue(recordedRequests.all { it.url.queryParameter("action") == "get_short_epg" })

        // +3 more hours (total 6.5h): Movies (6h), Series (6h), and EPG (3h since 3.5h) sync; Live(12h)/Cat(24h)/Account(24h) stay fresh
        recordedRequests.clear()
        fakeClockMs += (3.0 * 3_600_000L).toLong()
        val at6_5h = engine.syncAccount(
            account = account,
            requestedTypes = SyncContentType.entries.toList(),
            triggerReason = SyncTriggerReason.PERIODIC_BACKGROUND,
            settings = settings,
            onTmdbMetadataSync = {
                tmdbSyncCalls.incrementAndGet()
                true
            }
        )
        assertEquals(SyncOutcome.SUCCESS, at6_5h.outcomes[SyncContentType.MOVIES])
        assertEquals(SyncOutcome.SUCCESS, at6_5h.outcomes[SyncContentType.SERIES])
        assertEquals(SyncOutcome.SUCCESS, at6_5h.outcomes[SyncContentType.EPG])
        assertEquals(SyncOutcome.SKIPPED_FRESH, at6_5h.outcomes[SyncContentType.LIVE_TV])
        assertEquals(SyncOutcome.SKIPPED_FRESH, at6_5h.outcomes[SyncContentType.CATEGORIES])
        assertEquals(SyncOutcome.SKIPPED_FRESH, at6_5h.outcomes[SyncContentType.ACCOUNT_STATUS])

        // +6 more hours (total 12.5h): Live TV (12h) becomes due while Categories & Account Status (24h) stay fresh
        recordedRequests.clear()
        fakeClockMs += (6.0 * 3_600_000L).toLong()
        val at12_5h = engine.syncAccount(
            account = account,
            requestedTypes = SyncContentType.entries.toList(),
            triggerReason = SyncTriggerReason.PERIODIC_BACKGROUND,
            settings = settings
        )
        assertEquals(SyncOutcome.SUCCESS, at12_5h.outcomes[SyncContentType.LIVE_TV])
        assertEquals(SyncOutcome.SKIPPED_FRESH, at12_5h.outcomes[SyncContentType.CATEGORIES])
        assertEquals(SyncOutcome.SKIPPED_FRESH, at12_5h.outcomes[SyncContentType.ACCOUNT_STATUS])

        // +12 more hours (total 24.5h): Categories, Account Status, and TMDb metadata become due
        recordedRequests.clear()
        fakeClockMs += (12.0 * 3_600_000L).toLong()
        val at24_5h = engine.syncAccount(
            account = account,
            requestedTypes = SyncContentType.entries.toList(),
            triggerReason = SyncTriggerReason.PERIODIC_BACKGROUND,
            settings = settings,
            onTmdbMetadataSync = {
                tmdbSyncCalls.incrementAndGet()
                true
            }
        )
        assertEquals(SyncOutcome.SUCCESS, at24_5h.outcomes[SyncContentType.CATEGORIES])
        assertEquals(SyncOutcome.SUCCESS, at24_5h.outcomes[SyncContentType.ACCOUNT_STATUS])
        assertEquals(SyncOutcome.SUCCESS, at24_5h.outcomes[SyncContentType.TMDB_METADATA])
        assertEquals(2, tmdbSyncCalls.get())
    }

    @Test
    fun `http conditional etag and last modified return 304 not modified without overwriting cached data`() = runBlocking {
        val account = testAccount("acc_smart_1")
        repository.addAccount(account)
        responseHandler = { req -> buildDefaultXtreamResponse(req, etagSuffix = "v1", support304 = true) }
        val engine = buildTestEngine()

        // Initial fetch stores ETag and Last-Modified
        val first = engine.syncAccount(
            account = account,
            requestedTypes = listOf(SyncContentType.MOVIES, SyncContentType.ACCOUNT_STATUS),
            triggerReason = SyncTriggerReason.APP_STARTUP
        )
        assertEquals(SyncOutcome.SUCCESS, first.outcomes[SyncContentType.MOVIES])
        assertEquals("\"get_vod_streams_v1\"", store.getConditionalHeaders(account.id, "movies").etag)
        assertNotNull(store.getConditionalHeaders(account.id, "movies").lastModified)

        // Enrich cached movie with real TMDb plot and detected playback quality
        val beforeEnrich = repository.getItemById("vod_101")
        assertNotNull(beforeEnrich)
        repository.updateTmdbMetadata(
            beforeEnrich!!.copy(
                tmdbId = 9901,
                plot = "Verified TMDb synopsis for Inception.",
                streamIcon = "http://tmdb.local/poster.jpg",
                backdrop = "http://tmdb.local/backdrop.jpg",
                rating = 8.8
            )
        )
        repository.updateMediaQuality("vod_101", "4K UHD | HEVC | 60 FPS")

        // Advance 7 hours -> Movies sync sends If-None-Match & If-Modified-Since and receives 304 Not Modified
        recordedRequests.clear()
        fakeClockMs += 7 * 3_600_000L
        val second = engine.syncAccount(
            account = account,
            requestedTypes = listOf(SyncContentType.MOVIES),
            triggerReason = SyncTriggerReason.APP_STARTUP
        )

        assertEquals(SyncOutcome.NOT_MODIFIED, second.outcomes[SyncContentType.MOVIES])
        assertFalse(second.hadAnyCatalogChange)
        val vodReq = recordedRequests.first { it.url.queryParameter("action") == "get_vod_streams" }
        assertEquals("\"get_vod_streams_v1\"", vodReq.header("If-None-Match"))
        assertEquals("Wed, 21 Oct 2026 07:28:00 GMT", vodReq.header("If-Modified-Since"))

        // Cached movie retains its TMDb metadata and playback-detected quality untouched
        val cachedMovie = repository.getItemById("vod_101")
        assertNotNull(cachedMovie)
        assertEquals(9901, cachedMovie?.tmdbId)
        assertEquals("Verified TMDb synopsis for Inception.", cachedMovie?.plot)
        assertEquals("4K UHD | HEVC | 60 FPS", cachedMovie?.quality)
    }

    @Test
    fun `incremental diff preserves tmdb metadata and real quality while adding new and pruning removed items`() = runBlocking {
        val account = testAccount("acc_smart_1")
        repository.addAccount(account)
        val engine = buildTestEngine()

        // Initial sync loads vod_101 and vod_102
        engine.syncAccount(
            account = account,
            requestedTypes = listOf(SyncContentType.MOVIES),
            triggerReason = SyncTriggerReason.APP_STARTUP
        )
        assertEquals(2, repository.countLocalMedia(account.id, "MOVIE"))

        // Enrich vod_101 with TMDb metadata and real playback quality
        val initialVod = repository.getItemById("vod_101")
        assertNotNull(initialVod)
        repository.updateTmdbMetadata(
            initialVod!!.copy(
                tmdbId = 27205,
                plot = "A thief steals secrets using dream-sharing technology.",
                streamIcon = "http://tmdb.local/inception.jpg",
                backdrop = "http://tmdb.local/inception_bg.jpg",
                rating = 8.9
            )
        )
        repository.updateMediaQuality("vod_101", "4K UHD | HEVC")

        // Server updates catalog: vod_101 stays (with empty server plot), vod_102 removed, vod_103 added
        val updatedMoviesJson = """
            [
              {"stream_id":101,"name":"Inception (2010)","stream_icon":"http://iptv.local/101.jpg","category_id":"10","rating":"8.0","container_extension":"mp4","added":"1740000000"},
              {"stream_id":103,"name":"Interstellar (2014) 4K","stream_icon":"http://iptv.local/103.jpg","category_id":"10","rating":"8.7","container_extension":"mp4","added":"1745000000"}
            ]
        """.trimIndent()
        responseHandler = { req -> buildDefaultXtreamResponse(req, etagSuffix = "v2", moviesJson = updatedMoviesJson) }

        fakeClockMs += 7 * 3_600_000L
        val res = engine.syncAccount(
            account = account,
            requestedTypes = listOf(SyncContentType.MOVIES),
            triggerReason = SyncTriggerReason.APP_STARTUP
        )
        assertEquals(SyncOutcome.SUCCESS, res.outcomes[SyncContentType.MOVIES])
        assertTrue(res.hadAnyCatalogChange)

        // Total is 2 (vod_101 preserved + vod_103 added; vod_102 pruned AFTER success)
        assertEquals(2, repository.countLocalMedia(account.id, "MOVIE"))
        assertNull(repository.getItemById("vod_102"))
        assertNotNull(repository.getItemById("vod_103"))

        // vod_101 kept its TMDb metadata and real detected quality!
        val keptMovie = repository.getItemById("vod_101")
        assertNotNull(keptMovie)
        assertEquals(27205, keptMovie?.tmdbId)
        assertEquals("A thief steals secrets using dream-sharing technology.", keptMovie?.plot)
        assertEquals("4K UHD | HEVC", keptMovie?.quality)
    }

    @Test
    fun `network failure preserves local cache does not mark success and applies exponential retry backoff`() = runBlocking {
        val account = testAccount("acc_smart_1")
        repository.addAccount(account)
        val engine = buildTestEngine()

        // Seed initial movies successfully
        val initialTime = fakeClockMs
        engine.syncAccount(
            account = account,
            requestedTypes = listOf(SyncContentType.MOVIES),
            triggerReason = SyncTriggerReason.APP_STARTUP
        )
        assertEquals(2, repository.countLocalMedia(account.id, "MOVIE"))
        assertEquals(initialTime, store.getLastSuccessMs(account.id, SyncContentType.MOVIES))

        // Advance 7 hours and simulate HTTP 500 failure
        fakeClockMs += 7 * 3_600_000L
        val fail1Time = fakeClockMs
        responseHandler = { req -> jsonResponse(req, code = 500, body = "Internal Server Error") }

        val fail1 = engine.syncAccount(
            account = account,
            requestedTypes = listOf(SyncContentType.MOVIES),
            triggerReason = SyncTriggerReason.APP_STARTUP
        )

        assertEquals(SyncOutcome.FAILED, fail1.outcomes[SyncContentType.MOVIES])
        // Cached movies MUST remain intact
        assertEquals(2, repository.countLocalMedia(account.id, "MOVIE"))
        // Last success timestamp MUST NOT advance on failure
        assertEquals(initialTime, store.getLastSuccessMs(account.id, SyncContentType.MOVIES))
        assertEquals(1, store.getConsecutiveFailures(account.id, SyncContentType.MOVIES))
        // Backoff #1 = 5 minutes
        assertEquals(fail1Time + 5 * 60_000L, store.getNextRetryAtMs(account.id, SyncContentType.MOVIES))

        // Immediate retry (2 min later) MUST be skipped by backoff without making HTTP calls
        recordedRequests.clear()
        fakeClockMs += 2 * 60_000L
        val blocked = engine.syncAccount(
            account = account,
            requestedTypes = listOf(SyncContentType.MOVIES),
            triggerReason = SyncTriggerReason.APP_STARTUP
        )
        assertEquals(SyncOutcome.SKIPPED_BACKOFF, blocked.outcomes[SyncContentType.MOVIES])
        assertEquals(0, blocked.totalNetworkRequests)
        assertEquals(0, recordedRequests.size)

        // After 5 minutes elapsed, 2nd failure doubles backoff to 10 minutes
        fakeClockMs = fail1Time + 5 * 60_000L + 1_000L
        val fail2Time = fakeClockMs
        val fail2 = engine.syncAccount(
            account = account,
            requestedTypes = listOf(SyncContentType.MOVIES),
            triggerReason = SyncTriggerReason.APP_STARTUP
        )
        assertEquals(SyncOutcome.FAILED, fail2.outcomes[SyncContentType.MOVIES])
        assertEquals(2, store.getConsecutiveFailures(account.id, SyncContentType.MOVIES))
        assertEquals(fail2Time + 10 * 60_000L, store.getNextRetryAtMs(account.id, SyncContentType.MOVIES))
        // Cache still intact after repeated failures
        assertEquals(2, repository.countLocalMedia(account.id, "MOVIE"))
    }

    @Test
    fun `wifi only and battery saving constraints control automatic sync and interval doubling`() = runBlocking {
        val account = testAccount("acc_smart_1")
        repository.addAccount(account)
        val engine = buildTestEngine()

        // Seed data on Wi-Fi first
        engine.syncAccount(
            account = account,
            requestedTypes = listOf(SyncContentType.MOVIES),
            triggerReason = SyncTriggerReason.APP_STARTUP
        )

        // Advance 7 hours (past default 6h), switch to cellular, enable Wi-Fi Only
        fakeClockMs += 7 * 3_600_000L
        fakeConstraints = SyncDeviceConstraints(isNetworkAvailable = true, isWifiConnected = false, isBatteryLowOrPowerSave = false)
        val wifiOnlySettings = UserSettings(syncWifiOnly = true)
        recordedRequests.clear()

        val skippedWifi = engine.syncAccount(
            account = account,
            requestedTypes = listOf(SyncContentType.MOVIES),
            triggerReason = SyncTriggerReason.APP_STARTUP,
            settings = wifiOnlySettings
        )
        assertEquals(SyncOutcome.SKIPPED_CONSTRAINTS, skippedWifi.outcomes[SyncContentType.MOVIES])
        assertEquals(0, recordedRequests.size)

        // Enable Battery Saving Sync on Wi-Fi: 6h interval doubles to 12h -> still fresh at 7h!
        fakeConstraints = SyncDeviceConstraints(isNetworkAvailable = true, isWifiConnected = true, isBatteryLowOrPowerSave = false)
        val batterySavingSettings = UserSettings(batterySavingSyncMode = true)
        val doubledIntervalRes = engine.syncAccount(
            account = account,
            requestedTypes = listOf(SyncContentType.MOVIES),
            triggerReason = SyncTriggerReason.PERIODIC_BACKGROUND,
            settings = batterySavingSettings
        )
        assertEquals(SyncOutcome.SKIPPED_FRESH, doubledIntervalRes.outcomes[SyncContentType.MOVIES])
        assertEquals(0, recordedRequests.size)

        // Advance to 13h (> doubled 12h), but battery is low -> paused by Battery Saving Sync
        fakeClockMs += 6 * 3_600_000L
        fakeConstraints = SyncDeviceConstraints(isNetworkAvailable = true, isWifiConnected = true, isBatteryLowOrPowerSave = true)
        val lowBatteryRes = engine.syncAccount(
            account = account,
            requestedTypes = listOf(SyncContentType.MOVIES),
            triggerReason = SyncTriggerReason.PERIODIC_BACKGROUND,
            settings = batterySavingSettings
        )
        assertEquals(SyncOutcome.SKIPPED_CONSTRAINTS, lowBatteryRes.outcomes[SyncContentType.MOVIES])
        assertEquals(0, recordedRequests.size)

        // Restore healthy battery -> now syncs cleanly
        fakeConstraints = SyncDeviceConstraints(isNetworkAvailable = true, isWifiConnected = true, isBatteryLowOrPowerSave = false)
        val healthyRes = engine.syncAccount(
            account = account,
            requestedTypes = listOf(SyncContentType.MOVIES),
            triggerReason = SyncTriggerReason.PERIODIC_BACKGROUND,
            settings = batterySavingSettings
        )
        assertEquals(SyncOutcome.SUCCESS, healthyRes.outcomes[SyncContentType.MOVIES])
    }

    @Test
    fun `favorites and watch history sync only when changed and clear dirty flag on success`() = runBlocking {
        val account = testAccount("acc_smart_1")
        repository.addAccount(account)
        val engine = buildTestEngine()
        val favSyncCounter = AtomicInteger(0)

        // Clean state -> skipped
        store.saveCredentialsFingerprint(account)
        val cleanRun = engine.syncAccount(
            account = account,
            requestedTypes = listOf(SyncContentType.FAVORITES_HISTORY),
            triggerReason = SyncTriggerReason.APP_STARTUP,
            onFavoritesHistorySync = {
                favSyncCounter.incrementAndGet()
                true
            }
        )
        assertEquals(SyncOutcome.SKIPPED_UNCHANGED, cleanRun.outcomes[SyncContentType.FAVORITES_HISTORY])
        assertEquals(0, favSyncCounter.get())

        // Mark dirty (simulating favorite toggle / watch progress change) -> syncs once and clears flag
        store.markFavoritesOrHistoryDirty(account.id, fakeClockMs)
        assertTrue(store.isFavoritesOrHistoryDirty(account.id))

        val dirtyRun = engine.syncAccount(
            account = account,
            requestedTypes = listOf(SyncContentType.FAVORITES_HISTORY),
            triggerReason = SyncTriggerReason.APP_STARTUP,
            onFavoritesHistorySync = {
                favSyncCounter.incrementAndGet()
                true
            }
        )
        assertEquals(SyncOutcome.SUCCESS, dirtyRun.outcomes[SyncContentType.FAVORITES_HISTORY])
        assertEquals(1, favSyncCounter.get())
        assertFalse(store.isFavoritesOrHistoryDirty(account.id))

        // Next launch without further edits -> skipped again
        val afterCleanRun = engine.syncAccount(
            account = account,
            requestedTypes = listOf(SyncContentType.FAVORITES_HISTORY),
            triggerReason = SyncTriggerReason.APP_STARTUP,
            onFavoritesHistorySync = {
                favSyncCounter.incrementAndGet()
                true
            }
        )
        assertEquals(SyncOutcome.SKIPPED_UNCHANGED, afterCleanRun.outcomes[SyncContentType.FAVORITES_HISTORY])
        assertEquals(1, favSyncCounter.get())
    }

    @Test
    fun `account isolation keeps timestamps etags separate and credentials change triggers full sync`() = runBlocking {
        val accA = testAccount(id = "acc_smart_1", username = "alice", password = "pwA")
        val accB = testAccount(id = "acc_smart_2", username = "bob", password = "pwB")
        repository.addAccount(accA)
        repository.addAccount(accB)
        responseHandler = { req -> buildDefaultXtreamResponse(req, etagSuffix = "v1", support304 = true) }
        val engine = buildTestEngine()

        // Sync Account A
        engine.syncAccount(
            account = accA,
            requestedTypes = listOf(SyncContentType.MOVIES),
            triggerReason = SyncTriggerReason.APP_STARTUP
        )
        assertTrue(store.getLastSuccessMs(accA.id, SyncContentType.MOVIES) > 0L)
        assertEquals(0L, store.getLastSuccessMs(accB.id, SyncContentType.MOVIES))

        // Account B has its own independent sync state and must still fetch
        val resB = engine.syncAccount(
            account = accB,
            requestedTypes = listOf(SyncContentType.MOVIES),
            triggerReason = SyncTriggerReason.APP_STARTUP
        )
        assertEquals(SyncOutcome.SUCCESS, resB.outcomes[SyncContentType.MOVIES])
        assertTrue(store.getLastSuccessMs(accB.id, SyncContentType.MOVIES) > 0L)

        // Credentials change on Account A clears conditional headers and triggers full sync (no If-None-Match)
        recordedRequests.clear()
        val accAUpdatedCreds = accA.copy(password = "new_pw_A")
        val credChangeRun = engine.syncAccount(
            account = accAUpdatedCreds,
            requestedTypes = listOf(SyncContentType.MOVIES),
            triggerReason = SyncTriggerReason.APP_STARTUP
        )
        assertEquals(SyncTriggerReason.CREDENTIALS_CHANGED, credChangeRun.triggerReason)
        assertEquals(SyncOutcome.SUCCESS, credChangeRun.outcomes[SyncContentType.MOVIES])
        val req = recordedRequests.first { it.url.queryParameter("action") == "get_vod_streams" }
        assertNull("Full sync on credentials change must bypass stale ETag", req.header("If-None-Match"))

        // Clearing Account A metadata does NOT affect Account B
        store.clearAccount(accA.id)
        assertEquals(0L, store.getLastSuccessMs(accA.id, SyncContentType.MOVIES))
        assertTrue(store.getLastSuccessMs(accB.id, SyncContentType.MOVIES) > 0L)
    }

    @Test
    fun `manual sync now full refresh and single type emit progress only for manual triggers`() = runBlocking {
        val account = testAccount("acc_smart_1")
        repository.addAccount(account)
        responseHandler = { req -> buildDefaultXtreamResponse(req, etagSuffix = "v1", support304 = true) }
        val engine = buildTestEngine()

        // Initial automatic sync (silent)
        val autoMessages = mutableListOf<String?>()
        engine.syncAccount(
            account = account,
            requestedTypes = listOf(SyncContentType.MOVIES, SyncContentType.SERIES),
            triggerReason = SyncTriggerReason.APP_STARTUP,
            onManualProgress = { autoMessages.add(it) }
        )
        assertTrue(autoMessages.isEmpty())

        // Manual Sync Now (even while fresh) runs, emits progress, and uses ETag (304)
        recordedRequests.clear()
        val manualSyncMessages = mutableListOf<String?>()
        val syncNowRes = engine.syncAccount(
            account = account,
            requestedTypes = listOf(SyncContentType.MOVIES),
            triggerReason = SyncTriggerReason.MANUAL_SINGLE_TYPE,
            onManualProgress = { manualSyncMessages.add(it) }
        )
        assertEquals(SyncOutcome.NOT_MODIFIED, syncNowRes.outcomes[SyncContentType.MOVIES])
        assertTrue("Manual sync must report visible progress", manualSyncMessages.any { !it.isNullOrBlank() })
        assertNull("Manual progress clears on completion", manualSyncMessages.last())

        // Manual Full Refresh clears ETag and downloads fresh full payload (HTTP 200, no If-None-Match)
        recordedRequests.clear()
        val fullRefreshMessages = mutableListOf<String?>()
        val fullRefreshRes = engine.syncAccount(
            account = account,
            requestedTypes = listOf(SyncContentType.MOVIES),
            triggerReason = SyncTriggerReason.MANUAL_FULL_REFRESH,
            onManualProgress = { fullRefreshMessages.add(it) }
        )
        assertEquals(SyncOutcome.SUCCESS, fullRefreshRes.outcomes[SyncContentType.MOVIES])
        val fullReq = recordedRequests.first { it.url.queryParameter("action") == "get_vod_streams" }
        assertNull("Manual Full Refresh must not send If-None-Match", fullReq.header("If-None-Match"))
        assertTrue(fullRefreshMessages.any { !it.isNullOrBlank() })
        assertNull(fullRefreshMessages.last())
        assertFalse(engine.uiState.value.isManualSyncRunning)
    }

    @Test
    fun `custom sync intervals persist in user settings and control sync schedule`() {
        val custom = UserSettings(
            smartAutoSyncEnabled = false,
            syncWifiOnly = true,
            batterySavingSyncMode = true,
            syncIntervalMoviesHours = 12,
            syncIntervalSeriesHours = 8,
            syncIntervalLiveHours = 24,
            syncIntervalCategoriesHours = 48,
            syncIntervalEpgHours = 6,
            syncIntervalTmdbHours = 48,
            syncIntervalAccountHours = 12
        )
        repository.saveUserSettings(custom)
        val reloaded = repository.getUserSettings()

        assertFalse(reloaded.smartAutoSyncEnabled)
        assertTrue(reloaded.syncWifiOnly)
        assertTrue(reloaded.batterySavingSyncMode)
        assertEquals(12, reloaded.syncIntervalMoviesHours)
        assertEquals(8, reloaded.syncIntervalSeriesHours)
        assertEquals(24, reloaded.syncIntervalLiveHours)
        assertEquals(48, reloaded.syncIntervalCategoriesHours)
        assertEquals(6, reloaded.syncIntervalEpgHours)
        assertEquals(48, reloaded.syncIntervalTmdbHours)
        assertEquals(12, reloaded.syncIntervalAccountHours)

        // Effective interval doubles when Battery Saving Sync is active
        assertEquals(24L * SyncContentType.ONE_HOUR_MS, SyncContentType.MOVIES.effectiveIntervalMs(reloaded))
        assertEquals(12L * SyncContentType.ONE_HOUR_MS, SyncContentType.EPG.effectiveIntervalMs(reloaded))
    }

    @Test
    fun `viewmodel tab switching and theme updates do not trigger visible sync progress`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = KrasViewModel(app)

        // Switch tabs / apply filters repeatedly -> syncProgress stays strictly null
        viewModel.filterMovies(categoryId = "all")
        viewModel.filterMovies(categoryId = "all")
        viewModel.filterSeries(categoryId = "all")
        viewModel.filterSeries(categoryId = "all")
        viewModel.filterLive(categoryId = "all")
        viewModel.filterLive(categoryId = "all")

        // Theme change -> must not trigger catalog synchronization
        val currentSettings = viewModel.userSettings.value
        viewModel.updateSettings(
            currentSettings.copy(
                premiumTheme = com.example.data.model.KrasPremiumTheme.ROYAL_GOLD,
                accentColorIndex = 2
            )
        )

        assertNull("Tab switching and theme updates must never trigger visible sync banners", viewModel.syncProgress.value)
        assertFalse(viewModel.smartSyncState.value.isManualSyncRunning)
        viewModel.playerWrapper.release()
    }

    companion object {
        private val DEFAULT_MOVIES_JSON = """
            [
              {"stream_id":101,"name":"Inception (2010)","stream_icon":"http://iptv.local/101.jpg","category_id":"10","rating":"8.0","container_extension":"mp4","added":"1740000000"},
              {"stream_id":102,"name":"The Dark Knight (2008)","stream_icon":"http://iptv.local/102.jpg","category_id":"10","rating":"9.0","container_extension":"mp4","added":"1740000500"}
            ]
        """.trimIndent()

        private val DEFAULT_SERIES_JSON = """
            [
              {"series_id":201,"name":"Breaking Bad","cover":"http://iptv.local/201.jpg","category_id":"20","rating":"9.5","last_modified":"1740001000"}
            ]
        """.trimIndent()

        private val DEFAULT_LIVE_JSON = """
            [
              {"stream_id":301,"name":"Bein Sports 4K 1","stream_icon":"http://iptv.local/301.jpg","category_id":"30"}
            ]
        """.trimIndent()
    }
}
