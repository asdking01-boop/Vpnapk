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
}

