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
}
