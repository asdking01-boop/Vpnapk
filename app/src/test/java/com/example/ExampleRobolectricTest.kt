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
}
