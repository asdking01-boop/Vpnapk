package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.KrasRepository
import com.example.data.model.Account
import com.example.data.model.MediaType
import com.example.data.model.WatchProgress
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Kras TV", appName)
  }

  @Test
  fun `repository accounts persistence`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val repo = KrasRepository(context)

    val demo = repo.getActiveAccount()
    assertNotNull(demo)
    assertTrue(demo.name.isNotEmpty())

    val testAccount = Account(
      id = "test_1",
      name = "Test User",
      serverUrl = "http://iptv.example.com:8080",
      username = "testuser",
      password = "pass123",
      isActive = true
    )
    repo.addAccount(testAccount)
    val active = repo.getActiveAccount()
    assertEquals("test_1", active.id)
    assertEquals("Test User", active.name)
  }

  @Test
  fun `repository watch history tracking`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val repo = KrasRepository(context)

    val progress = WatchProgress(
      id = "item_1",
      mediaId = "item_1",
      title = "Cyberpunk 2099",
      type = MediaType.MOVIE,
      posterUrl = "http://example.com/poster.jpg",
      positionMs = 60_000,
      durationMs = 120_000
    )
    repo.saveWatchProgress(progress)

    val history = repo.getWatchHistory()
    assertTrue(history.any { it.mediaId == "item_1" })
    assertEquals(0.5f, history.first { it.mediaId == "item_1" }.progressFraction, 0.01f)
  }

  @Test
  fun `repository media caching for instant launch`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val repo = KrasRepository(context)

    val item = com.example.data.model.MediaItem(
      id = "cached_m1",
      streamId = 501,
      name = "Matrix Resurrections",
      title = "Matrix Resurrections",
      type = MediaType.MOVIE,
      rating = 8.5,
      year = "2024"
    )
    repo.cacheMediaList("cached_movies", listOf(item))

    val cached = repo.getCachedMediaList("cached_movies")
    assertEquals(1, cached.size)
    assertEquals("cached_m1", cached[0].id)
    assertEquals("Matrix Resurrections", cached[0].name)
  }

  @Test
  fun `crash report preference persistence and dismissal`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val prefs = context.getSharedPreferences(KrasApplication.PREFS_CRASH, Context.MODE_PRIVATE)

    // Simulate saving a crash report
    val fakeReport = "=== Kras TV Crash Report ===\nException: NullPointerException: test crash"
    prefs.edit().putString(KrasApplication.KEY_CRASH_REPORT, fakeReport).commit()

    // Verify stored
    val stored = prefs.getString(KrasApplication.KEY_CRASH_REPORT, null)
    assertNotNull(stored)
    assertEquals(fakeReport, stored)

    // Simulate dismissal
    prefs.edit().remove(KrasApplication.KEY_CRASH_REPORT).commit()
    assertNull(prefs.getString(KrasApplication.KEY_CRASH_REPORT, null))
  }

  @Test
  fun `user settings silence notifications persistence`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val repo = KrasRepository(context)

    val initial = repo.getUserSettings()
    assertFalse(initial.silenceNotificationsWhileWatching)

    val updated = initial.copy(silenceNotificationsWhileWatching = true)
    repo.saveUserSettings(updated)

    val reloaded = repo.getUserSettings()
    assertTrue(reloaded.silenceNotificationsWhileWatching)
  }

  @Test
  fun `interruption filter state persistence and restoration`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val repo = KrasRepository(context)

    assertNull(repo.getSavedInterruptionFilter())

    repo.saveInterruptionFilter(1) // NotificationManager.INTERRUPTION_FILTER_ALL
    assertEquals(1, repo.getSavedInterruptionFilter())

    repo.saveInterruptionFilter(null)
    assertNull(repo.getSavedInterruptionFilter())
  }

  @Test
  fun `room database batch insert and bounded query`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val repo = KrasRepository(context)

    kotlinx.coroutines.runBlocking {
      val testAccountId = "acc_batch_test"
      val batch = (1..100).map { i ->
        com.example.data.local.entity.MediaItemEntity(
          id = "vod_$i",
          accountId = testAccountId,
          streamId = i,
          name = "Movie $i",
          title = "Movie $i",
          type = "MOVIE",
          posterUrl = "http://example.com/poster_$i.jpg",
          backdropUrl = "",
          categoryId = if (i % 2 == 0) "cat_action" else "cat_comedy",
          categoryName = if (i % 2 == 0) "Action" else "Comedy",
          rating = 5.0 + (i % 50) * 0.1,
          year = "2024",
          resolution = "4K",
          quality = "HDR",
          streamUrl = "http://example.com/movie/$i.mp4",
          containerExtension = "mp4",
          addedTimestamp = System.currentTimeMillis() + i
        )
      }

      // Batch insert into Room
      repo.insertMediaBatch(batch)

      // Test bounded query
      val topRated = repo.getTopRated(testAccountId, "MOVIE", limit = 10)
      assertEquals(10, topRated.size)
      assertTrue(topRated.first().rating >= topRated.last().rating)

      // Test paged query
      val page1 = repo.queryMediaPaged(testAccountId, "MOVIE", "all", "", 0.0, "All", limit = 20, offset = 0)
      assertEquals(20, page1.size)

      val page2 = repo.queryMediaPaged(testAccountId, "MOVIE", "all", "", 0.0, "All", limit = 20, offset = 20)
      assertEquals(20, page2.size)
      assertNotEquals(page1.first().id, page2.first().id)

      // Test clear old rows
      repo.clearOldRows(testAccountId, "MOVIE")
      val afterClear = repo.getTopRated(testAccountId, "MOVIE", limit = 10)
      assertEquals(0, afterClear.size)
    }
  }

  @Test
  fun `channel name normalizer extracts groupKey and numeric seq accurately`() {
    val norm1 = com.example.data.local.ChannelNameNormalizer.normalize("Bein Sports 4K 8")
    assertEquals("bein sports 4k", norm1.groupKey)
    assertEquals(8, norm1.seq)
    assertTrue(norm1.hasNumber)

    val norm2 = com.example.data.local.ChannelNameNormalizer.normalize("AR | Bein Sports 4K ١٠")
    assertEquals("bein sports 4k", norm2.groupKey)
    assertEquals(10, norm2.seq)
    assertTrue(norm2.hasNumber)

    val norm3 = com.example.data.local.ChannelNameNormalizer.normalize("⚽ - AR: Bein Xtra 4K 2")
    assertEquals("bein xtra 4k", norm3.groupKey)
    assertEquals(2, norm3.seq)
    assertTrue(norm3.hasNumber)

    // Channel without standalone channel number keeps 4K in groupKey and seq = 0
    val normNoNum = com.example.data.local.ChannelNameNormalizer.normalize("Kras Cinema 4K")
    assertEquals("kras cinema 4k", normNoNum.groupKey)
    assertEquals(0, normNoNum.seq)
    assertFalse(normNoNum.hasNumber)
  }

  @Test
  fun `room live tv smart server and az ordering across categories search and favorites`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val repo = KrasRepository(context)

    kotlinx.coroutines.runBlocking {
      val accId = "acc_live_sort_test"
      repo.clearOldRows(accId, "LIVE")

      // Server returns channels out of numeric order: 8, 9, 1, 10, 2, plus Xtra 3, 1, 2, plus unnumbered
      val rawServerChannels = listOf(
        com.example.data.model.MediaItem(id = "ch_bs8", streamId = 1, name = "Bein Sports 4K 8", type = MediaType.LIVE, categoryId = "cat_sports"),
        com.example.data.model.MediaItem(id = "ch_bs9", streamId = 2, name = "AR: Bein Sports 4K ٩", type = MediaType.LIVE, categoryId = "cat_sports"),
        com.example.data.model.MediaItem(id = "ch_bx3", streamId = 3, name = "Bein Xtra 4K 3", type = MediaType.LIVE, categoryId = "cat_sports"),
        com.example.data.model.MediaItem(id = "ch_bs1", streamId = 4, name = "Bein Sports 4K 1", type = MediaType.LIVE, categoryId = "cat_sports"),
        com.example.data.model.MediaItem(id = "ch_bs10", streamId = 5, name = "Bein Sports 4K 10", type = MediaType.LIVE, categoryId = "cat_sports"),
        com.example.data.model.MediaItem(id = "ch_news", streamId = 6, name = "Skyline World News", type = MediaType.LIVE, categoryId = "cat_news"),
        com.example.data.model.MediaItem(id = "ch_bx1", streamId = 7, name = "Bein Xtra 4K 1", type = MediaType.LIVE, categoryId = "cat_sports"),
        com.example.data.model.MediaItem(id = "ch_bs2", streamId = 8, name = "AR | Bein Sports 4K 2", type = MediaType.LIVE, categoryId = "cat_sports"),
        com.example.data.model.MediaItem(id = "ch_bx2", streamId = 9, name = "Bein Xtra 4K 2", type = MediaType.LIVE, categoryId = "cat_sports"),
        com.example.data.model.MediaItem(id = "ch_eng2", streamId = 10, name = "Bein Sports English 4K 2", type = MediaType.LIVE, categoryId = "cat_sports"),
        com.example.data.model.MediaItem(id = "ch_eng1", streamId = 11, name = "Bein Sports English 4K 1", type = MediaType.LIVE, categoryId = "cat_sports")
      )

      val entities = com.example.data.local.entity.MediaItemEntity.fromMediaItemList(rawServerChannels, accId)
      repo.insertMediaBatch(entities)

      // 1. SMART order (default): ORDER BY groupOrder ASC, seq ASC, name ASC
      val smartAll = repo.queryMediaPaged(
        accountId = accId,
        type = "LIVE",
        categoryId = "all",
        query = "",
        minRating = 0.0,
        year = "All",
        limit = 50,
        offset = 0,
        liveChannelOrder = com.example.data.model.LiveChannelOrder.SMART
      )
      val smartIds = smartAll.map { it.id }
      assertEquals(
        listOf(
          "ch_bs1", "ch_bs2", "ch_bs8", "ch_bs9", "ch_bs10",
          "ch_bx1", "ch_bx2", "ch_bx3",
          "ch_news",
          "ch_eng1", "ch_eng2"
        ),
        smartIds
      )

      // 2. Category query in SMART order
      val smartSports = repo.queryMediaPaged(
        accountId = accId,
        type = "LIVE",
        categoryId = "cat_sports",
        query = "",
        minRating = 0.0,
        year = "All",
        limit = 50,
        offset = 0,
        liveChannelOrder = com.example.data.model.LiveChannelOrder.SMART
      )
      assertEquals(
        listOf(
          "ch_bs1", "ch_bs2", "ch_bs8", "ch_bs9", "ch_bs10",
          "ch_bx1", "ch_bx2", "ch_bx3",
          "ch_eng1", "ch_eng2"
        ),
        smartSports.map { it.id }
      )

      // 3. SERVER order: ORDER BY serverOrder ASC, name ASC
      val serverAll = repo.queryMediaPaged(
        accountId = accId,
        type = "LIVE",
        categoryId = "all",
        query = "",
        minRating = 0.0,
        year = "All",
        limit = 50,
        offset = 0,
        liveChannelOrder = com.example.data.model.LiveChannelOrder.SERVER
      )
      assertEquals(rawServerChannels.map { it.id }, serverAll.map { it.id })

      // 4. A_TO_Z order: ORDER BY groupKey ASC, seq ASC, name ASC
      val azAll = repo.queryMediaPaged(
        accountId = accId,
        type = "LIVE",
        categoryId = "all",
        query = "",
        minRating = 0.0,
        year = "All",
        limit = 50,
        offset = 0,
        liveChannelOrder = com.example.data.model.LiveChannelOrder.A_TO_Z
      )
      // "bein sports 4k" < "bein sports english 4k" < "bein xtra 4k" < "skyline world news"
      assertEquals(
        listOf(
          "ch_bs1", "ch_bs2", "ch_bs8", "ch_bs9", "ch_bs10",
          "ch_eng1", "ch_eng2",
          "ch_bx1", "ch_bx2", "ch_bx3",
          "ch_news"
        ),
        azAll.map { it.id }
      )

      // 5. Global Search results respect channel ordering
      val searchBein = repo.searchAll(accId, "Bein Sports 4K", com.example.data.model.LiveChannelOrder.SMART)
      assertEquals(listOf("ch_bs1", "ch_bs2", "ch_bs8", "ch_bs9", "ch_bs10"), searchBein.map { it.id })

      // 6. Favorites respect channel ordering
      val ch10 = smartAll.first { it.id == "ch_bs10" }
      val ch2 = smartAll.first { it.id == "ch_bs2" }
      val ch1 = smartAll.first { it.id == "ch_bs1" }
      repo.toggleFavorite(ch10)
      repo.toggleFavorite(ch2)
      repo.toggleFavorite(ch1)

      val favSmart = repo.getFavorites(com.example.data.model.LiveChannelOrder.SMART)
        .filter { it.mediaId in listOf("ch_bs1", "ch_bs2", "ch_bs10") }
      assertEquals(listOf("ch_bs1", "ch_bs2", "ch_bs10"), favSmart.map { it.mediaId })

      // 7. Settings persistence for liveChannelOrder
      val settings = repo.getUserSettings()
      assertEquals(com.example.data.model.LiveChannelOrder.SMART, settings.liveChannelOrder)
      repo.saveUserSettings(settings.copy(liveChannelOrder = com.example.data.model.LiveChannelOrder.A_TO_Z))
      assertEquals(com.example.data.model.LiveChannelOrder.A_TO_Z, repo.getUserSettings().liveChannelOrder)
    }
  }

  @Test
  fun `quality detector matches whole tokens and maps real video specs accurately`() {
    val qDetector = com.example.data.local.QualityDetector

    // Whole word/token matching from name and category
    assertEquals("4K UHD | HEVC | 50 FPS", qDetector.detectFromNameAndCategory("Bein Sports 4K HEVC 50FPS 1", "Sports"))
    assertEquals("8K", qDetector.detectFromNameAndCategory("Ultra Demo 8K", "Cinema"))
    assertEquals("4K UHD", qDetector.detectFromNameAndCategory("Movie 2160p", "VOD"))
    assertEquals("FHD | 60 FPS", qDetector.detectFromNameAndCategory("Action Channel Full HD 60fps", "Live"))
    assertEquals("FHD", qDetector.detectFromNameAndCategory("Series 1080", "Drama FHD"))
    assertEquals("HD", qDetector.detectFromNameAndCategory("News 720p", "News"))
    assertEquals("SD", qDetector.detectFromNameAndCategory("Classic TV 480p", "Retro"))
    assertEquals("SD", qDetector.detectFromNameAndCategory("Mobile Stream 360", "General"))

    // Category-based detection when name has no quality token
    assertEquals("4K UHD | HEVC", qDetector.detectFromNameAndCategory("Premier League 1", "UK | 4K UHD HEVC"))

    // Must NOT match tokens inside other words (e.g. "4K" inside "M4K2" or "HD" inside "SHADOW")
    assertEquals("", qDetector.detectFromNameAndCategory("Shadow of the M4K2", "Drama"))
    assertEquals("", qDetector.detectFromNameAndCategory("Unknown Channel Name", "General"))

    // Real video height mapping
    assertEquals("8K", qDetector.mapHeightToBadge(4320))
    assertEquals("4K UHD", qDetector.mapHeightToBadge(2160))
    assertEquals("FHD", qDetector.mapHeightToBadge(1080))
    assertEquals("HD", qDetector.mapHeightToBadge(720))
    assertEquals("SD", qDetector.mapHeightToBadge(480))
    assertEquals("", qDetector.mapHeightToBadge(0))

    // Real specs builder and player overlay
    assertEquals("FHD | HEVC | 50 FPS | 5.1", qDetector.buildFromRealSpecs(1080, 50f, "hevc", 6))
    assertEquals("1080p 50 FPS", qDetector.formatPlayerResolutionOverlay(1080, 50f))
  }

  @Test
  fun `room quality filter sort by quality and real playback override`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val repo = KrasRepository(context)

    kotlinx.coroutines.runBlocking {
      val accId = "acc_quality_test"
      repo.clearOldRows(accId, "MOVIE")

      val items = listOf(
        com.example.data.model.MediaItem(
          id = "m_sd", streamId = 1, name = "Retro Film 480p", type = MediaType.MOVIE, categoryId = "cat_1", rating = 9.0
        ),
        com.example.data.model.MediaItem(
          id = "m_4k", streamId = 2, name = "Blockbuster 4K HEVC", type = MediaType.MOVIE, categoryId = "cat_1", rating = 7.5
        ),
        com.example.data.model.MediaItem(
          id = "m_hd", streamId = 3, name = "Documentary 720p", type = MediaType.MOVIE, categoryId = "cat_1", rating = 8.0
        ),
        com.example.data.model.MediaItem(
          id = "m_fhd", streamId = 4, name = "Thriller 1080p 60FPS", type = MediaType.MOVIE, categoryId = "cat_1", rating = 8.5
        ),
        com.example.data.model.MediaItem(
          id = "m_unknown", streamId = 5, name = "Mystery Drama", type = MediaType.MOVIE, categoryId = "cat_1", rating = 9.5
        )
      )

      val entities = com.example.data.local.entity.MediaItemEntity.fromMediaItemList(items, accId)
      repo.insertMediaBatch(entities)

      // Filter by 4K
      val only4K = repo.queryMediaPaged(
        accountId = accId, type = "MOVIE", categoryId = "cat_1", query = "", minRating = 0.0, year = "All",
        limit = 20, offset = 0, qualityFilter = "4K", sortByQuality = false
      )
      assertEquals(listOf("m_4k"), only4K.map { it.id })

      // Filter by HD (must match only HD, not FHD or 4K UHD)
      val onlyHD = repo.queryMediaPaged(
        accountId = accId, type = "MOVIE", categoryId = "cat_1", query = "", minRating = 0.0, year = "All",
        limit = 20, offset = 0, qualityFilter = "HD", sortByQuality = false
      )
      assertEquals(listOf("m_hd"), onlyHD.map { it.id })

      // Sort by quality (highest first: 4K UHD -> FHD -> HD -> SD -> unknown)
      val sortedByQuality = repo.queryMediaPaged(
        accountId = accId, type = "MOVIE", categoryId = "cat_1", query = "", minRating = 0.0, year = "All",
        limit = 20, offset = 0, qualityFilter = "All", sortByQuality = true
      )
      assertEquals(listOf("m_4k", "m_fhd", "m_hd", "m_sd", "m_unknown"), sortedByQuality.map { it.id })

      // Real playback detection overrides unknown or name-based guess in Room
      repo.updateMediaQuality("m_unknown", "8K | HEVC | 60 FPS")
      val afterOverride = repo.queryMediaPaged(
        accountId = accId, type = "MOVIE", categoryId = "cat_1", query = "", minRating = 0.0, year = "All",
        limit = 20, offset = 0, qualityFilter = "All", sortByQuality = true
      )
      assertEquals("m_unknown", afterOverride.first().id)
      assertEquals("8K | HEVC | 60 FPS", afterOverride.first().quality)
    }
  }

  @Test
  fun `github release updater parses tag_name release notes and KrasTV apk asset`() {
    assertEquals("asdking01-boop", BuildConfig.GITHUB_OWNER)
    assertEquals("Vpnapk", BuildConfig.GITHUB_REPO)
    assertEquals(
      "https://api.github.com/repos/asdking01-boop/Vpnapk/releases/latest",
      com.example.updater.AppUpdater.latestReleaseApiUrl
    )

    assertEquals(12, com.example.updater.AppUpdater.parseTagVersionNumber("v12"))
    assertEquals(5, com.example.updater.AppUpdater.parseTagVersionNumber("V5"))

    val sampleReleaseJson = """
      {
        "tag_name": "v12",
        "body": "- Faster 4K playback\n- Updated EPG parser",
        "assets": [
          {
            "name": "KrasTV.apk",
            "browser_download_url": "https://github.com/asdking01-boop/Vpnapk/releases/download/v12/KrasTV.apk",
            "size": 18452992
          }
        ]
      }
    """.trimIndent()

    // When tag_name version (12) > currentVersionCode (1), UpdateAvailable is returned
    val updateResult = com.example.updater.AppUpdater.parseReleaseJson(sampleReleaseJson, currentVersionCode = 1)
    assertTrue(updateResult is com.example.updater.ReleaseCheckResult.UpdateAvailable)
    val release = (updateResult as com.example.updater.ReleaseCheckResult.UpdateAvailable).release
    assertEquals("v12", release.tagName)
    assertEquals(12, release.versionNumber)
    assertEquals("KrasTV.apk", release.assetName)
    assertEquals(18452992L, release.assetSize)
    assertTrue(release.body.contains("Faster 4K playback"))

    // When tag_name version (12) <= currentVersionCode (12), UpToDate is returned
    val upToDateResult = com.example.updater.AppUpdater.parseReleaseJson(sampleReleaseJson, currentVersionCode = 12)
    assertTrue(upToDateResult is com.example.updater.ReleaseCheckResult.UpToDate)

    // When KrasTV.apk asset is missing, MissingAsset is returned
    val noApkJson = """
      {
        "tag_name": "v15",
        "body": "Notes",
        "assets": []
      }
    """.trimIndent()
    val missingResult = com.example.updater.AppUpdater.parseReleaseJson(noApkJson, currentVersionCode = 1)
    assertTrue(missingResult is com.example.updater.ReleaseCheckResult.MissingAsset)
  }

  @Test
  fun `updater cleans up old apks after update and builds fileprovider install intent`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val repo = KrasRepository(context)

    // Default autoCheckUpdates is true
    assertTrue(repo.getUserSettings().autoCheckUpdates)
    repo.saveUserSettings(repo.getUserSettings().copy(autoCheckUpdates = false))
    assertFalse(repo.getUserSettings().autoCheckUpdates)

    // Simulate an old APK in cacheDir/updates from versionCode 1
    repo.saveLastKnownVersionCode(1)
    val updatesDir = com.example.updater.AppUpdater.getUpdatesDir(context)
    val dummyApk = java.io.File(updatesDir, "KrasTV.apk")
    dummyApk.writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04))
    assertTrue(dummyApk.exists())

    // Verify FileProvider URI and install intent creation
    val installIntent = com.example.updater.AppUpdater.createInstallApkIntent(context, dummyApk)
    assertEquals(android.content.Intent.ACTION_VIEW, installIntent.action)
    assertEquals("application/vnd.android.package-archive", installIntent.type)
    assertNotNull(installIntent.data)
    assertTrue((installIntent.flags and android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)

    // Simulate app launch on upgraded versionCode 2 -> old APK must be deleted
    com.example.updater.AppUpdater.cleanupOldApksAfterUpdate(context, repo, currentVersionCode = 2)
    assertFalse(dummyApk.exists())
    assertEquals(2, repo.getLastKnownVersionCode())
  }

  @Test
  fun `unified player wrapper reuses exoplayer instance and transitions states cleanly`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val wrapper = com.example.player.ExoPlayerWrapper(context)

    // Verify single instance reuse across identical buffer settings
    val p1 = wrapper.initPlayer("Large")
    val p2 = wrapper.initPlayer("Large")
    assertSame(p1, p2)

    // Initial state is PREPARING before first frame
    wrapper.setPreparingState()
    assertEquals(com.example.player.PlayerUiState.PREPARING, wrapper.playerUiState.value)
    assertFalse(wrapper.hasRenderedFirstFrame.value)
    assertNull(wrapper.errorMessage.value)

    // Blank URL transitions cleanly to ERROR state without infinite spinner
    wrapper.play("")
    assertEquals(com.example.player.PlayerUiState.ERROR, wrapper.playerUiState.value)
    assertFalse(wrapper.isBuffering.value)
    assertNotNull(wrapper.errorMessage.value)

    // Valid stream preparation enters PREPARING on the same player instance
    wrapper.play("https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8", startPositionMs = 15_000L)
    assertEquals(com.example.player.PlayerUiState.PREPARING, wrapper.playerUiState.value)
    assertNull(wrapper.errorMessage.value)
    assertSame(p1, wrapper.getPlayer())

    // Cleanup
    wrapper.release()
  }

  @Test
  fun `latest 20 movies per category sorts by server date added not release year and deduplicates`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val repo = KrasRepository(context)

    kotlinx.coroutines.runBlocking {
      val accId = "acc_latest20_movies"
      repo.clearOldRows(accId, "MOVIE")

      // Create 25 movies in cat_action where a 1999 classic was added MOST RECENTLY to the server,
      // and a 2025 movie was added earlier. Also include a duplicate title/streamId.
      val baseTime = 1_740_000_000_000L
      val actionMovies = (1..25).map { i ->
        com.example.data.model.MediaItem(
          id = "act_$i",
          streamId = 1000 + i,
          name = "Action Movie $i",
          title = "Action Movie $i",
          type = MediaType.MOVIE,
          categoryId = "cat_action",
          categoryName = "Action",
          year = if (i == 25) "1999" else "2025", // #25 is older release year (1999) but newest server addedTimestamp!
          addedTimestamp = baseTime + (i * 60_000L),
          hasServerAddedDate = true
        )
      }
      val duplicateActionMovie = com.example.data.model.MediaItem(
        id = "act_dup",
        streamId = 1025, // duplicate streamId and title of #25
        name = "Action Movie 25",
        title = "Action Movie 25",
        type = MediaType.MOVIE,
        categoryId = "cat_action",
        categoryName = "Action",
        year = "1999",
        addedTimestamp = baseTime + (25 * 60_000L),
        hasServerAddedDate = true
      )
      // Category with fewer than 20 movies (only 3 movies)
      val sciFiMovies = (1..3).map { i ->
        com.example.data.model.MediaItem(
          id = "scifi_$i",
          streamId = 2000 + i,
          name = "SciFi Film $i",
          title = "SciFi Film $i",
          type = MediaType.MOVIE,
          categoryId = "cat_scifi",
          categoryName = "Sci-Fi",
          year = "2024",
          addedTimestamp = baseTime + (i * 100_000L),
          hasServerAddedDate = true
        )
      }

      val entities = com.example.data.local.entity.MediaItemEntity.fromMediaItemList(
        actionMovies + duplicateActionMovie + sciFiMovies,
        accId
      )
      repo.insertMediaBatch(entities)

      // 1. Category with > 20 movies returns exactly 20 unique movies, newest server addition first
      val latestAction = repo.getLatestByCategory(accId, "MOVIE", "cat_action", limit = 20)
      assertEquals(20, latestAction.size)
      // First item must be Action Movie 25 (year 1999, newest addedTimestamp), proving date_added != release year
      assertEquals("act_25", latestAction.first().id)
      assertEquals("1999", latestAction.first().year)
      assertTrue(latestAction.first().hasServerAddedDate)
      // Last of the top 20 must be Action Movie 6
      assertEquals("act_6", latestAction.last().id)
      // Strictly descending addedTimestamp
      for (idx in 0 until latestAction.size - 1) {
        assertTrue(latestAction[idx].addedTimestamp >= latestAction[idx + 1].addedTimestamp)
      }

      // 2. Category with < 20 movies returns all 3 without error
      val latestSciFi = repo.getLatestByCategory(accId, "MOVIE", "cat_scifi", limit = 20)
      assertEquals(3, latestSciFi.size)
      assertEquals(listOf("scifi_3", "scifi_2", "scifi_1"), latestSciFi.map { it.id })
    }
  }

  @Test
  fun `missing server date_added uses persistent first-seen timestamp across catalog refreshes`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val repo = KrasRepository(context)

    kotlinx.coroutines.runBlocking {
      val accId = "acc_first_seen_test"
      repo.clearOldRows(accId, "SERIES")

      // Series without server date_added (addedTimestamp = 0L, hasServerAddedDate = false)
      val seriesNoDate = com.example.data.model.MediaItem(
        id = "ser_nodate_1",
        streamId = 901,
        name = "Mystery Series",
        title = "Mystery Series",
        type = MediaType.SERIES,
        categoryId = "cat_drama",
        categoryName = "Drama",
        year = "2024",
        addedTimestamp = 0L,
        hasServerAddedDate = false
      )
      // Series WITH server date_added
      val serverDateMs = com.example.data.api.XtreamClient.parseServerTimestampMs("2025-02-15 14:30:00")
      assertTrue(serverDateMs > 0L)
      val seriesWithServerDate = com.example.data.model.MediaItem(
        id = "ser_srv_1",
        streamId = 902,
        name = "Server Dated Series",
        title = "Server Dated Series",
        type = MediaType.SERIES,
        categoryId = "cat_drama",
        categoryName = "Drama",
        year = "2022",
        addedTimestamp = serverDateMs,
        hasServerAddedDate = true
      )

      repo.insertMediaBatch(
        com.example.data.local.entity.MediaItemEntity.fromMediaItemList(
          listOf(seriesNoDate, seriesWithServerDate),
          accId
        )
      )

      val firstLoad = repo.getLatestByCategory(accId, "SERIES", "cat_drama", limit = 20)
      assertEquals(2, firstLoad.size)
      // Server-dated item is prioritized and marked hasServerAddedDate = true
      val loadedServerItem = firstLoad.first { it.id == "ser_srv_1" }
      val loadedFallbackItem = firstLoad.first { it.id == "ser_nodate_1" }
      assertTrue(loadedServerItem.hasServerAddedDate)
      assertFalse(loadedFallbackItem.hasServerAddedDate)
      assertTrue(loadedFallbackItem.addedTimestamp > 0L)
      val originalFirstSeenTimestamp = loadedFallbackItem.addedTimestamp

      // Simulate re-syncing playlist later: clearOldRows snapshots first-seen timestamps,
      // and re-inserting the same item with addedTimestamp = 0L preserves its original first-seen timestamp!
      repo.clearOldRows(accId, "SERIES")
      repo.insertMediaBatch(
        com.example.data.local.entity.MediaItemEntity.fromMediaItemList(
          listOf(seriesNoDate, seriesWithServerDate),
          accId
        )
      )

      val secondLoad = repo.getLatestByCategory(accId, "SERIES", "cat_drama", limit = 20)
      val reloadedFallbackItem = secondLoad.first { it.id == "ser_nodate_1" }
      assertFalse(reloadedFallbackItem.hasServerAddedDate)
      assertEquals(originalFirstSeenTimestamp, reloadedFallbackItem.addedTimestamp)
    }
  }

  @Test
  fun `persistent live tv channel switching updates activeLiveChannel without opening separate fullscreen screen`() {
    val app = ApplicationProvider.getApplicationContext<android.app.Application>()
    val viewModel = com.example.viewmodel.KrasViewModel(app)

    val ch1 = com.example.data.model.MediaItem(
      id = "live_test_1", streamId = 101, name = "Channel 1", type = MediaType.LIVE,
      categoryId = "live_sports", streamUrl = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
    )
    val ch2 = com.example.data.model.MediaItem(
      id = "live_test_2", streamId = 102, name = "Channel 2", type = MediaType.LIVE,
      categoryId = "live_sports", streamUrl = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
    )
    val ch3 = com.example.data.model.MediaItem(
      id = "live_test_3", streamId = 103, name = "Channel 3", type = MediaType.LIVE,
      categoryId = "live_sports", streamUrl = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
    )

    // Select Channel 1 in persistent Live TV view
    viewModel.selectLiveChannel(ch1)
    assertEquals("live_test_1", viewModel.activeLiveChannel.value?.id)
    // Must NOT set nowPlayingItem (which would open the separate fullscreen VOD overlay and hide the channel list)
    assertNull(viewModel.nowPlayingItem.value)
    assertFalse(viewModel.isLiveFullscreen.value)
    val playerInstance1 = viewModel.playerWrapper.getPlayer()
    assertNotNull(playerInstance1)

    // Switch to Channel 2 and Channel 3 sequentially -> same player instance, activeLiveChannel updated
    viewModel.selectLiveChannel(ch2)
    assertEquals("live_test_2", viewModel.activeLiveChannel.value?.id)
    assertNull(viewModel.nowPlayingItem.value)
    assertSame(playerInstance1, viewModel.playerWrapper.getPlayer())

    viewModel.selectLiveChannel(ch3)
    assertEquals("live_test_3", viewModel.activeLiveChannel.value?.id)
    assertSame(playerInstance1, viewModel.playerWrapper.getPlayer())

    // Enter fullscreen and switch channel inside fullscreen overlay drawer -> stays fullscreen and switches channel
    viewModel.setLiveFullscreen(true)
    assertTrue(viewModel.isLiveFullscreen.value)
    viewModel.selectLiveChannel(ch1, enterFullscreen = true)
    assertEquals("live_test_1", viewModel.activeLiveChannel.value?.id)
    assertTrue(viewModel.isLiveFullscreen.value)

    // Exit fullscreen -> returns to persistent channel list while keeping Channel 1 active
    viewModel.setLiveFullscreen(false)
    assertFalse(viewModel.isLiveFullscreen.value)
    assertEquals("live_test_1", viewModel.activeLiveChannel.value?.id)

    viewModel.stopLiveChannelPlayback()
    viewModel.playerWrapper.release()
  }
}



