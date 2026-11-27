package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.api.TmdbCastMember
import com.example.data.api.TmdbDiagnosticStatus
import com.example.data.api.TmdbDirectClient
import com.example.data.api.TmdbEpisodeMetadata
import com.example.data.api.TmdbErrorSanitizer
import com.example.data.api.TmdbMediaMetadata
import com.example.data.api.TmdbMetadataMatcher
import com.example.data.api.TmdbPersonBiography
import com.example.data.api.TmdbProxyClient
import com.example.data.api.TmdbSeasonSummary
import com.example.data.api.TmdbSimilarItem
import com.example.data.local.KrasRepository
import com.example.data.local.ManualTmdbBinding
import com.example.data.local.TmdbMetadataCacheStore
import com.example.data.model.Episode
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.model.Season
import com.example.data.model.SeriesDetails
import com.example.data.model.TmdbMetadataLanguage
import com.example.data.model.UserSettings
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TmdbIntegrationRobolectricTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    // =========================================================================
    // 1. Smart Title Cleaning, Bilingual Splitting & Strict Match Verification
    // =========================================================================

    @Test
    fun cleanIptvTitle_stripsPrefixesTagsDotsUnderscoresAndExtractsYear() {
        val scene = TmdbMetadataMatcher.cleanIptvTitle("Inception.2010.1080p.BluRay.x265.HEVC")
        assertEquals("Inception", scene.cleanTitle)
        assertEquals("2010", scene.extractedYear)

        val prefixed = TmdbMetadataMatcher.cleanIptvTitle("NF | EN - The Dark Knight [4K HDR] (2008) MULTI-SUB")
        assertEquals("The Dark Knight", prefixed.cleanTitle)
        assertEquals("2008", prefixed.extractedYear)

        val underscored = TmdbMetadataMatcher.cleanIptvTitle("Breaking_Bad_S01E01_720p_WEB-DL")
        assertEquals("Breaking Bad", underscored.cleanTitle)

        val arabicTagged = TmdbMetadataMatcher.cleanIptvTitle("أفلام | الماتريكس مترجم 4K (1999)")
        assertEquals("الماتريكس", arabicTagged.cleanTitle)
        assertEquals("1999", arabicTagged.extractedYear)

        // Year-only movie titles must NOT be stripped to empty
        val yearTitle = TmdbMetadataMatcher.cleanIptvTitle("1917")
        assertEquals("1917", yearTitle.cleanTitle)
        assertEquals("", yearTitle.extractedYear)
    }

    @Test
    fun buildSearchQueries_splitsBilingualTitlesIntoEnglishAndArabicVariants() {
        val queries = TmdbMetadataMatcher.buildSearchQueries("EN | The Matrix | الماتريكس (1999) 4K")
        assertTrue("Expected Latin variant 'The Matrix', got $queries", queries.contains("The Matrix"))
        assertTrue("Expected Arabic variant 'الماتريكس', got $queries", queries.contains("الماتريكس"))
    }

    @Test
    fun strictMatching_acceptsExactMatchAndRejectsWrongMovieOrFarYear() {
        val goodScore = TmdbMetadataMatcher.scoreCandidateMatch(
            queryTitle = "EN | Inception (2010) 4K",
            queryYear = "2010",
            candidateTitle = "Inception",
            candidateOriginalTitle = "Inception",
            candidateYear = "2010"
        )
        assertTrue("Exact match should score >= 0.85, got $goodScore", goodScore >= 0.85)

        val wrongTitleScore = TmdbMetadataMatcher.scoreCandidateMatch(
            queryTitle = "Inception (2010)",
            queryYear = "2010",
            candidateTitle = "Insidious",
            candidateOriginalTitle = "Insidious",
            candidateYear = "2010"
        )
        assertTrue("Unrelated movie should be rejected (< 0.65), got $wrongTitleScore", wrongTitleScore < 0.65)

        val wrongYearScore = TmdbMetadataMatcher.scoreCandidateMatch(
            queryTitle = "Dune (2021)",
            queryYear = "2021",
            candidateTitle = "Dune",
            candidateOriginalTitle = "Dune",
            candidateYear = "1984"
        )
        assertTrue("Far-year remake mismatch should score lower than exact year, got $wrongYearScore", wrongYearScore < 0.72)
    }

    // =========================================================================
    // 2. Backdrop & Image Normalization (Never Portrait Copy, Resolves Relative)
    // =========================================================================

    @Test
    fun imageNormalization_resolvesRelativePathsAndRejectsNullOrPortraitCopy() {
        assertEquals(
            "https://image.tmdb.org/t/p/w1280/s3TBrRGB1iav7gFOCNx3H31MoES.jpg",
            TmdbMetadataMatcher.normalizeTmdbImageUrl("/s3TBrRGB1iav7gFOCNx3H31MoES.jpg", "w1280")
        )
        assertEquals("", TmdbMetadataMatcher.normalizeTmdbImageUrl("null"))
        assertEquals("", TmdbMetadataMatcher.normalizeTmdbImageUrl("N/A"))
        assertEquals("", TmdbMetadataMatcher.normalizeTmdbImageUrl("   "))

        val poster = "https://iptv.example.com/posters/inception.jpg"
        assertFalse(
            "Portrait poster copied as backdrop must be rejected as invalid backdrop",
            TmdbMetadataMatcher.isValidBackdropUrl(poster, poster)
        )
        assertTrue(
            "Distinct widescreen TMDb backdrop must be accepted",
            TmdbMetadataMatcher.isValidBackdropUrl("https://image.tmdb.org/t/p/w1280/8ZTVqvKDQ8emSGUEMjsS4yHAwrp.jpg", poster)
        )
    }

    // =========================================================================
    // 3. TmdbProxyClient Smart Candidate Selection + Rich Metadata + Fallback
    // =========================================================================

    @Test
    fun proxyClient_selectsBestCandidateNotResults0_andParsesRichFieldsWithEnglishFallback() = runBlocking {
        val requests = CopyOnWriteArrayList<String>()
        val mockHttp = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                val url = req.url.toString()
                requests.add(url)
                val body = when {
                    url.contains("/api/tmdb/search/movie") -> """
                        {
                          "results": [
                            {
                              "id": 999999,
                              "title": "Inception: The Cobol Job",
                              "original_title": "Inception: The Cobol Job",
                              "release_date": "2010-12-07",
                              "vote_average": 7.1,
                              "poster_path": "/wrong.jpg"
                            },
                            {
                              "id": 27205,
                              "title": "Inception",
                              "original_title": "Inception",
                              "release_date": "2010-07-15",
                              "vote_average": 8.4,
                              "poster_path": "/ljsZTbVsrQSqZgWeep2B1QiDKuh.jpg",
                              "backdrop_path": "/8ZTVqvKDQ8emSGUEMjsS4yHAwrp.jpg"
                            }
                          ]
                        }
                    """.trimIndent()

                    url.contains("/api/tmdb/movie/27205") && url.contains("language=ar") -> """
                        {
                          "tmdbId": 27205,
                          "title": "استهلال",
                          "originalTitle": "Inception",
                          "overview": "",
                          "posterUrl": "https://image.tmdb.org/t/p/w500/ljsZTbVsrQSqZgWeep2B1QiDKuh.jpg",
                          "backdropUrl": "https://image.tmdb.org/t/p/w1280/8ZTVqvKDQ8emSGUEMjsS4yHAwrp.jpg",
                          "rating": 8.37,
                          "releaseYear": "2010",
                          "releaseDate": "2010-07-15",
                          "genres": "أكشن, خيال علمي",
                          "cast": "Leonardo DiCaprio, Joseph Gordon-Levitt",
                          "director": "Christopher Nolan",
                          "runtimeMinutes": 148,
                          "castMembers": [
                            {
                              "id": 6193,
                              "name": "Leonardo DiCaprio",
                              "character": "Dom Cobb",
                              "profileUrl": "https://image.tmdb.org/t/p/w185/wo2hJpn04vbtmh0B9utCFdsQhxM.jpg"
                            }
                          ],
                          "trailerYoutubeKey": "",
                          "similar": []
                        }
                    """.trimIndent()

                    url.contains("/api/tmdb/movie/27205") -> """
                        {
                          "tmdbId": 27205,
                          "title": "Inception",
                          "originalTitle": "Inception",
                          "overview": "Cobb, a skilled thief who commits corporate espionage by infiltrating the subconscious of his targets is offered a chance to regain his old life.",
                          "posterUrl": "https://image.tmdb.org/t/p/w500/ljsZTbVsrQSqZgWeep2B1QiDKuh.jpg",
                          "backdropUrl": "https://image.tmdb.org/t/p/w1280/8ZTVqvKDQ8emSGUEMjsS4yHAwrp.jpg",
                          "rating": 8.37,
                          "releaseYear": "2010",
                          "releaseDate": "2010-07-15",
                          "genres": "Action, Science Fiction",
                          "cast": "Leonardo DiCaprio, Joseph Gordon-Levitt",
                          "director": "Christopher Nolan",
                          "runtimeMinutes": 148,
                          "castMembers": [
                            {
                              "id": 6193,
                              "name": "Leonardo DiCaprio",
                              "character": "Dom Cobb",
                              "profileUrl": "https://image.tmdb.org/t/p/w185/wo2hJpn04vbtmh0B9utCFdsQhxM.jpg"
                            }
                          ],
                          "trailerYoutubeKey": "YoHD9XEInc0",
                          "similar": [
                            {
                              "tmdbId": 157336,
                              "title": "Interstellar",
                              "posterUrl": "https://image.tmdb.org/t/p/w500/gEU2QniE6E77NI6lCU6MxlNBvIx.jpg",
                              "backdropUrl": "https://image.tmdb.org/t/p/w1280/xJHokMbljvjADYdit5fK5VQsXEG.jpg",
                              "releaseYear": "2014",
                              "rating": 8.4,
                              "isSeries": false
                            }
                          ]
                        }
                    """.trimIndent()

                    else -> """{"error": "unexpected path"}"""
                }
                jsonResponse(req, 200, body)
            })
            .build()

        val client = TmdbProxyClient(httpClient = mockHttp)
        val matched = client.fetchAccurateMediaMetadata(
            rawProxyUrl = "https://kras-tmdb-proxy.asdking01.workers.dev",
            rawTitle = "VIP | EN - Inception.2010.4K.HDR.HEVC",
            year = "2010",
            isSeries = false,
            tmdbId = 0,
            preferArabic = true
        )

        assertNotNull("Expected matched metadata", matched)
        val meta = matched!!
        // Must pick 27205 (exact match), NOT 999999 (results[0] spin-off)
        assertEquals(27205, meta.tmdbId)
        // Must request language=ar-SA for localized Arabic metadata
        assertTrue(
            "Expected ar-SA request in $requests",
            requests.any { it.contains("language=ar-SA") }
        )
        // Must keep original/English movie title even when Arabic metadata is requested
        assertEquals("Inception", meta.title)
        assertEquals("Inception", meta.originalTitle)
        assertEquals("Christopher Nolan", meta.director)
        assertEquals(148, meta.runtimeMinutes)
        assertEquals("https://image.tmdb.org/t/p/w1280/8ZTVqvKDQ8emSGUEMjsS4yHAwrp.jpg", meta.backdropUrl)
        // Arabic genres localized while blank Arabic overview falls back to English
        assertTrue(meta.genres.contains("أكشن"))
        assertTrue(meta.overview.contains("Cobb, a skilled thief"))
        assertEquals("YoHD9XEInc0", meta.trailerYoutubeKey)
        assertEquals(1, meta.castMembers.size)
        assertEquals("Leonardo DiCaprio", meta.castMembers.first().name)
        assertTrue(meta.castMembers.first().profileUrl.contains("w185"))
        assertEquals(1, meta.similarItems.size)
        assertEquals("Interstellar", meta.similarItems.first().title)

        // Verify enrichMediaItem NEVER copies poster into backdrop when backdrop is blank
        val posterOnlyMeta = meta.copy(backdropUrl = "")
        val baseItem = MediaItem(
            id = "vod_1",
            name = "Inception",
            streamId = 1,
            streamUrl = "http://iptv/1.mp4",
            streamIcon = "http://iptv/poster.jpg",
            backdrop = "",
            type = MediaType.MOVIE
        )
        val enriched = TmdbProxyClient.enrichMediaItem(baseItem, posterOnlyMeta)
        assertEquals("", enriched.backdrop)
        assertEquals("Christopher Nolan", enriched.director)
    }

    // =========================================================================
    // 4. TmdbDirectClient Dual Auth (v3 Key vs v4 Bearer) + Rich Append Parsing
    // =========================================================================

    @Test
    fun directClient_supportsV4BearerTokenAndParsesCastPhotosTrailerSimilar() = runBlocking {
        var capturedAuthHeader: String? = null
        var capturedUrl = ""

        val mockHttp = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                capturedAuthHeader = req.header("Authorization")
                capturedUrl = req.url.toString()
                val json = """
                    {
                      "id": 1396,
                      "name": "Breaking Bad",
                      "original_name": "Breaking Bad",
                      "overview": "A chemistry teacher diagnosed with inoperable lung cancer turns to manufacturing methamphetamine.",
                      "poster_path": "/ztkUQFLlC19CCMYHW9o1zWhJRNq.jpg",
                      "backdrop_path": "/tsRy63Mu5cu8etL1X7ZLyf7UP1M.jpg",
                      "vote_average": 8.9,
                      "first_air_date": "2008-01-20",
                      "episode_run_time": [47],
                      "genres": [{"id": 18, "name": "Drama"}, {"id": 80, "name": "Crime"}],
                      "created_by": [{"id": 66633, "name": "Vince Gilligan"}],
                      "credits": {
                        "cast": [
                          {"id": 17419, "name": "Bryan Cranston", "character": "Walter White", "profile_path": "/aGSvZg7uITJEXVeCetaTy49U1N4.jpg"}
                        ],
                        "crew": []
                      },
                      "videos": {
                        "results": [
                          {"site": "YouTube", "type": "Trailer", "official": true, "key": "HhesaQXLuRY"}
                        ]
                      },
                      "recommendations": {
                        "results": [
                          {"id": 60059, "name": "Better Call Saul", "poster_path": "/fC2HDm5t0kHl7mTm7jxMR31b7by.jpg", "backdrop_path": "/hPea3Qy5Gd6z4kJLUruBbwAH8Rm.jpg", "first_air_date": "2015-02-08", "vote_average": 8.7}
                        ]
                      },
                      "seasons": [
                        {"season_number": 1, "name": "Season 1", "episode_count": 7, "overview": "Season 1 overview", "poster_path": "/1BP4xYv9ZG4ZVHkL7ocOziBbSYH.jpg", "air_date": "2008-01-20"}
                      ]
                    }
                """.trimIndent()
                jsonResponse(req, 200, json)
            })
            .build()

        val directClient = TmdbDirectClient(httpClient = mockHttp)
        val fakeV4Jwt = "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiJ0ZXN0In0.signatureTokenValue"
        val meta = directClient.fetchMediaDetailsById(
            apiKey = fakeV4Jwt,
            tmdbId = 1396,
            isSeries = true,
            language = "en-US"
        )

        assertNotNull(meta)
        assertEquals("Bearer $fakeV4Jwt", capturedAuthHeader)
        assertFalse("v4 Bearer token must NOT be appended as ?api_key query param", capturedUrl.contains("api_key="))
        assertTrue(capturedUrl.contains("append_to_response=credits,videos,similar,recommendations"))
        assertEquals(1396, meta!!.tmdbId)
        assertEquals("Vince Gilligan", meta.director)
        assertEquals("HhesaQXLuRY", meta.trailerYoutubeKey)
        assertEquals("https://image.tmdb.org/t/p/w1280/tsRy63Mu5cu8etL1X7ZLyf7UP1M.jpg", meta.backdropUrl)
        assertEquals(1, meta.castMembers.size)
        assertEquals("https://image.tmdb.org/t/p/w185/aGSvZg7uITJEXVeCetaTy49U1N4.jpg", meta.castMembers.first().profileUrl)
        assertEquals(1, meta.similarItems.size)
        assertEquals("Better Call Saul", meta.similarItems.first().title)
        assertEquals(1, meta.seasons.size)
    }

    // =========================================================================
    // 5. Smart Metadata Cache Store & Credential Sanitizer
    // =========================================================================

    @Test
    fun cacheStore_persistsRichMetadataAndManualBindings_preservesCacheOnRefresh() {
        val store1 = TmdbMetadataCacheStore(context)
        store1.clearAllCache()

        val now = 1_760_000_000_000L
        val rich = TmdbMediaMetadata(
            tmdbId = 27205,
            title = "Inception",
            originalTitle = "Inception",
            overview = "A thief who steals corporate secrets through dream-sharing technology.",
            posterUrl = "https://image.tmdb.org/t/p/w500/poster.jpg",
            backdropUrl = "https://image.tmdb.org/t/p/w1280/backdrop.jpg",
            rating = 8.4,
            releaseYear = "2010",
            releaseDate = "2010-07-15",
            genres = "Action, Sci-Fi",
            cast = "Leonardo DiCaprio",
            director = "Christopher Nolan",
            runtimeMinutes = 148,
            seasons = listOf(TmdbSeasonSummary(1, "Season 1", 7, "Overview", "", "2010-01-01")),
            castMembers = listOf(TmdbCastMember(6193, "Leonardo DiCaprio", "Dom Cobb", "https://image.tmdb.org/t/p/w185/leo.jpg")),
            trailerYoutubeKey = "YoHD9XEInc0",
            similarItems = listOf(
                TmdbSimilarItem(
                    tmdbId = 157336,
                    title = "Interstellar",
                    releaseYear = "2014",
                    rating = 8.4,
                    isSeries = false
                )
            ),
            cachedAtMs = now,
            isManualMatch = true
        )

        store1.putForMediaItem("vod_27205", isSeries = false, preferArabic = false, metadata = rich, nowMs = now)
        store1.saveManualBinding(ManualTmdbBinding("vod_999", 603, false, "The Matrix", now))

        // Re-instantiate store from disk to verify persistence
        val store2 = TmdbMetadataCacheStore(context)
        val loaded = store2.getForMediaItem("vod_27205", 27205, isSeries = false, preferArabic = false)
        assertNotNull("Expected cached metadata after disk reload", loaded)
        assertEquals("YoHD9XEInc0", loaded!!.trailerYoutubeKey)
        assertEquals(1, loaded.castMembers.size)
        assertEquals("Dom Cobb", loaded.castMembers.first().character)
        assertEquals(1, loaded.similarItems.size)
        assertFalse(store2.isStale(loaded, now + 1000L))

        val binding = store2.getManualBinding("vod_999")
        assertNotNull(binding)
        assertEquals(603, binding!!.tmdbId)

        // Marking entries stale for manual refresh MUST preserve cached data (never wipe it)
        store2.markAllEntriesStaleForRefresh()
        val afterMarkStale = store2.getForMediaItem("vod_27205", 27205, isSeries = false, preferArabic = false)
        assertNotNull("Cached data must be preserved when marked stale for refresh", afterMarkStale)
        assertTrue("Entry should now be considered stale", store2.isStale(afterMarkStale, now + 1000L))
    }

    @Test
    fun errorSanitizer_redactsApiKeysBearerTokensJwtsAndSessionIds() {
        val raw = "Failed GET https://api.themoviedb.org/3/movie/27205?api_key=a1b2c3d4e5f60718293a4b5c6d7e8f90&session_id=sess_987654321 Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NSJ9.supersecret"
        val clean = TmdbErrorSanitizer.sanitize(raw)
        assertFalse("Must redact api_key value", clean.contains("a1b2c3d4e5f60718293a4b5c6d7e8f90"))
        assertFalse("Must redact session_id value", clean.contains("sess_987654321"))
        assertFalse("Must redact JWT token", clean.contains("supersecret"))
        assertTrue("Must contain redaction marker", clean.contains("[REDACTED]"))
    }

    // =========================================================================
    // 5B. Arabic TMDb Metadata Localization (ar-SA), Fallbacks & Separate Cache
    // =========================================================================

    @Test
    fun settingsMetadataLanguage_defaultsToArabicAndResolvesAllThreeModes() {
        val defaultSettings = UserSettings()
        assertEquals(TmdbMetadataLanguage.ARABIC, defaultSettings.tmdbMetadataLanguage)
        assertTrue(defaultSettings.resolvePreferArabicMetadata())
        assertEquals("ar-SA", defaultSettings.resolveTmdbLanguageTag())

        val englishSettings = defaultSettings.copy(tmdbMetadataLanguage = TmdbMetadataLanguage.ENGLISH)
        assertFalse(englishSettings.resolvePreferArabicMetadata())
        assertEquals("en-US", englishSettings.resolveTmdbLanguageTag())

        val followAppEnglish = defaultSettings.copy(
            language = "en",
            tmdbMetadataLanguage = TmdbMetadataLanguage.FOLLOW_APP
        )
        assertFalse(followAppEnglish.resolvePreferArabicMetadata())
        assertEquals("en-US", followAppEnglish.resolveTmdbLanguageTag())

        val followAppArabic = defaultSettings.copy(
            language = "ar",
            tmdbMetadataLanguage = TmdbMetadataLanguage.FOLLOW_APP
        )
        assertTrue(followAppArabic.resolvePreferArabicMetadata())
        assertEquals("ar-SA", followAppArabic.resolveTmdbLanguageTag())

        // Verify persistence through KrasRepository SharedPreferences
        val repo = KrasRepository(context)
        repo.saveUserSettings(englishSettings)
        assertEquals(TmdbMetadataLanguage.ENGLISH, repo.getUserSettings().tmdbMetadataLanguage)

        repo.saveUserSettings(followAppArabic)
        assertEquals(TmdbMetadataLanguage.FOLLOW_APP, repo.getUserSettings().tmdbMetadataLanguage)

        repo.saveUserSettings(defaultSettings)
        assertEquals(TmdbMetadataLanguage.ARABIC, repo.getUserSettings().tmdbMetadataLanguage)
    }

    @Test
    fun directClient_arSaLocalizesDescriptionGenresSeasonsEpisodesBioAndTrailer_preservingOriginalNames() = runBlocking {
        val requests = CopyOnWriteArrayList<String>()
        val mockHttp = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                val url = req.url.toString()
                requests.add(url)
                val body = when {
                    url.contains("/tv/1396/season/1") && url.contains("language=ar-SA") -> """
                        {
                          "season_number": 1,
                          "overview": "يبدأ والتر وايت رحلته الخطيرة بعد تشخيصه بالسرطان.",
                          "episodes": [
                            {"episode_number": 1, "name": "الحلقة الأولى", "overview": "يكتشف معلم كيمياء إصابته بالسرطان فيقرر تأمين مستقبل عائلته."},
                            {"episode_number": 2, "name": "القطة في الحقيبة", "overview": ""}
                          ]
                        }
                    """.trimIndent()

                    url.contains("/tv/1396/season/1") && url.contains("language=en-US") -> """
                        {
                          "season_number": 1,
                          "overview": "Walter White begins his dangerous journey.",
                          "episodes": [
                            {"episode_number": 1, "name": "Pilot", "overview": "A chemistry teacher gets a terminal diagnosis."},
                            {"episode_number": 2, "name": "Cat's in the Bag...", "overview": "Walt and Jesse try to dispose of the aftermath."}
                          ]
                        }
                    """.trimIndent()

                    url.contains("/person/17419") && url.contains("language=ar-SA") -> """
                        {
                          "id": 17419,
                          "name": "براين كرانستون",
                          "biography": "ممثل ومخرج ومنتج أمريكي اشتهر بدور والتر وايت.",
                          "birthday": "1956-03-07",
                          "place_of_birth": "Hollywood, California, USA",
                          "known_for_department": "Acting",
                          "profile_path": "/aGSvZg7uITJEXVeCetaTy49U1N4.jpg"
                        }
                    """.trimIndent()

                    url.contains("/person/99901") && url.contains("language=ar-SA") -> """
                        {
                          "id": 99901,
                          "name": "آرون بول",
                          "biography": "",
                          "birthday": "1979-08-27",
                          "place_of_birth": "Emmett, Idaho, USA",
                          "known_for_department": "Acting",
                          "profile_path": "/aaron.jpg"
                        }
                    """.trimIndent()

                    url.contains("/person/99901") && url.contains("language=en-US") -> """
                        {
                          "id": 99901,
                          "name": "Aaron Paul",
                          "biography": "Aaron Paul is an American actor best known for portraying Jesse Pinkman.",
                          "birthday": "1979-08-27",
                          "place_of_birth": "Emmett, Idaho, USA",
                          "known_for_department": "Acting",
                          "profile_path": "/aaron.jpg"
                        }
                    """.trimIndent()

                    url.contains("/tv/1396") && url.contains("language=ar-SA") -> """
                        {
                          "id": 1396,
                          "name": "اختلال ضال",
                          "original_name": "Breaking Bad",
                          "overview": "يتحول معلم كيمياء في مدرسة ثانوية إلى عالم الجريمة لتأمين مستقبل عائلته.",
                          "poster_path": "/ztkUQFLlC19CCMYHW9o1zWhJRNq.jpg",
                          "backdrop_path": "/tsRy63Mu5cu8etL1X7ZLyf7UP1M.jpg",
                          "vote_average": 8.9,
                          "first_air_date": "2008-01-20",
                          "episode_run_time": [47],
                          "genres": [{"id": 18, "name": "دراما"}, {"id": 80, "name": "جريمة"}],
                          "created_by": [{"id": 66633, "name": "فينس غيليغان", "original_name": "Vince Gilligan"}],
                          "credits": {
                            "cast": [
                              {
                                "id": 17419,
                                "name": "براين كرانستون",
                                "original_name": "Bryan Cranston",
                                "character": "Walter White",
                                "profile_path": "/aGSvZg7uITJEXVeCetaTy49U1N4.jpg"
                              }
                            ],
                            "crew": []
                          },
                          "videos": {
                            "results": [
                              {"site": "YouTube", "type": "Trailer", "official": true, "iso_639_1": "en", "key": "EN_TRAILER_KEY"},
                              {"site": "YouTube", "type": "Trailer", "official": true, "iso_639_1": "ar", "key": "AR_TRAILER_KEY"}
                            ]
                          },
                          "seasons": [
                            {"season_number": 1, "name": "الموسم 1", "episode_count": 7, "overview": "بداية قصة والتر وايت في الموسم الأول.", "poster_path": "/s1.jpg", "air_date": "2008-01-20"},
                            {"season_number": 2, "name": "الموسم 2", "episode_count": 13, "overview": "", "poster_path": "/s2.jpg", "air_date": "2009-03-08"}
                          ]
                        }
                    """.trimIndent()

                    url.contains("/tv/1396") && url.contains("language=en-US") -> """
                        {
                          "id": 1396,
                          "name": "Breaking Bad",
                          "original_name": "Breaking Bad",
                          "overview": "A high school chemistry teacher turns to crime.",
                          "poster_path": "/ztkUQFLlC19CCMYHW9o1zWhJRNq.jpg",
                          "backdrop_path": "/tsRy63Mu5cu8etL1X7ZLyf7UP1M.jpg",
                          "vote_average": 8.9,
                          "first_air_date": "2008-01-20",
                          "episode_run_time": [47],
                          "genres": [{"id": 18, "name": "Drama"}, {"id": 80, "name": "Crime"}],
                          "created_by": [{"id": 66633, "name": "Vince Gilligan"}],
                          "credits": {
                            "cast": [
                              {"id": 17419, "name": "Bryan Cranston", "original_name": "Bryan Cranston", "character": "Walter White", "profile_path": "/aGSvZg7uITJEXVeCetaTy49U1N4.jpg"}
                            ],
                            "crew": []
                          },
                          "videos": {
                            "results": [
                              {"site": "YouTube", "type": "Trailer", "official": true, "iso_639_1": "en", "key": "EN_TRAILER_KEY"}
                            ]
                          },
                          "seasons": [
                            {"season_number": 1, "name": "Season 1", "episode_count": 7, "overview": "English season 1 overview.", "poster_path": "/s1.jpg", "air_date": "2008-01-20"},
                            {"season_number": 2, "name": "Season 2", "episode_count": 13, "overview": "Walt and Jesse face escalating stakes in Season 2.", "poster_path": "/s2.jpg", "air_date": "2009-03-08"}
                          ]
                        }
                    """.trimIndent()

                    else -> "{}"
                }
                jsonResponse(req, 200, body)
            })
            .build()

        val client = TmdbDirectClient(httpClient = mockHttp)
        val fakeKey = "1234567890abcdef1234567890abcdef"

        // 1) Series metadata in ar-SA with English fallback for empty Season 2 overview
        val meta = client.fetchAccurateMediaMetadata(
            apiKey = fakeKey,
            rawTitle = "Breaking Bad",
            year = "2008",
            isSeries = true,
            tmdbId = 1396,
            preferArabic = true
        )
        assertNotNull(meta)
        val series = meta!!
        assertTrue("Expected ar-SA request", requests.any { it.contains("language=ar-SA") })
        assertEquals("Breaking Bad", series.title) // Original title preserved!
        assertEquals("Breaking Bad", series.originalTitle)
        assertTrue("Description must be Arabic", series.overview.contains("معلم كيمياء"))
        assertTrue("Genres must be Arabic", series.genres.contains("دراما") && series.genres.contains("جريمة"))
        assertEquals("Bryan Cranston", series.castMembers.first().name) // Original actor name preserved!
        assertEquals("Vince Gilligan", series.director)
        assertEquals("AR_TRAILER_KEY", series.trailerYoutubeKey) // Prefers Arabic trailer
        assertEquals("ar", series.trailerLanguage)
        // Season 1 keeps Arabic overview; Season 2 falls back to English overview (never empty)
        assertTrue(series.seasons.first { it.seasonNumber == 1 }.overview.contains("بداية قصة"))
        assertTrue(series.seasons.first { it.seasonNumber == 2 }.overview.contains("escalating stakes"))

        // 2) Season episode descriptions in ar-SA with per-episode English fallback
        val epList = client.fetchTvSeasonEpisodesLocalized(
            apiKey = fakeKey,
            tvId = 1396,
            seasonNumber = 1,
            preferArabic = true
        )
        assertTrue("Episode 1 should have Arabic overview", epList.first { it.episodeNumber == 1 }.overview.contains("معلم كيمياء"))
        assertTrue("Episode 2 blank Arabic should fall back to English", epList.first { it.episodeNumber == 2 }.overview.contains("dispose of the aftermath"))

        // 3) Actor biography in ar-SA preserving original actor name
        val arBio = client.fetchPersonBiography(
            apiKey = fakeKey,
            personId = 17419,
            fallbackName = "Bryan Cranston",
            fallbackCharacter = "Walter White",
            preferArabic = true
        )
        assertNotNull(arBio)
        assertEquals("Bryan Cranston", arBio!!.name)
        assertTrue("Bio should be Arabic", arBio.biography.contains("ممثل ومخرج"))
        assertEquals("ar-SA", arBio.languageTag)

        // 4) Actor biography falls back to English when Arabic bio is empty
        val fallbackBio = client.fetchPersonBiography(
            apiKey = fakeKey,
            personId = 99901,
            fallbackName = "Aaron Paul",
            fallbackCharacter = "Jesse Pinkman",
            preferArabic = true
        )
        assertNotNull(fallbackBio)
        assertEquals("Aaron Paul", fallbackBio!!.name)
        assertTrue("Fallback bio should be English", fallbackBio.biography.contains("Jesse Pinkman"))
        assertEquals("en-US", fallbackBio.languageTag)

        // 5) SeriesDetails enrichment propagates localized season overview + episode plot without touching original name
        val baseSeriesDetails = SeriesDetails(
            seriesId = 10,
            name = "Breaking Bad",
            seasons = listOf(
                Season(
                    seasonNumber = 1,
                    name = "Season 1",
                    episodeCount = 2,
                    episodes = listOf(
                        Episode(id = "ep1", episodeNum = 1, title = "Pilot", plot = ""),
                        Episode(id = "ep2", episodeNum = 2, title = "Cat's in the Bag...", plot = "")
                    )
                )
            )
        )
        val enrichedDetails = TmdbProxyClient.enrichSeriesDetails(
            details = baseSeriesDetails,
            metadata = series,
            seasonEpisodesMap = mapOf(1 to epList),
            overrideValidExistingPlot = true,
            preferArabic = true
        )
        assertEquals("Breaking Bad", enrichedDetails.name)
        assertTrue(enrichedDetails.plot.contains("معلم كيمياء"))
        assertTrue(enrichedDetails.genre.contains("دراما"))
        assertTrue(enrichedDetails.seasons.first().overview.contains("بداية قصة"))
        assertTrue(enrichedDetails.seasons.first().episodes[0].plot.contains("معلم كيمياء"))
        assertTrue(enrichedDetails.seasons.first().episodes[1].plot.contains("dispose of the aftermath"))
    }

    @Test
    fun cacheStore_storesArabicAndEnglishSeparatelyForMediaSeasonsAndActorBiographies() {
        val store1 = TmdbMetadataCacheStore(context)
        store1.clearAllCache()
        val now = 1_760_000_000_000L

        val arMeta = TmdbMediaMetadata(
            tmdbId = 27205,
            title = "Inception",
            originalTitle = "Inception",
            overview = "لص محترف يسرق الأسرار عبر تقنية مشاركة الأحلام.",
            posterUrl = "https://image.tmdb.org/t/p/w500/poster.jpg",
            backdropUrl = "https://image.tmdb.org/t/p/w1280/backdrop.jpg",
            rating = 8.4,
            releaseYear = "2010",
            releaseDate = "2010-07-15",
            genres = "أكشن، خيال علمي",
            cast = "Leonardo DiCaprio",
            director = "Christopher Nolan",
            trailerYoutubeKey = "AR_TRAILER",
            trailerLanguage = "ar",
            cachedAtMs = now
        )
        val enMeta = TmdbMediaMetadata(
            tmdbId = 27205,
            title = "Inception",
            originalTitle = "Inception",
            overview = "A skilled thief steals secrets through dream-sharing technology.",
            posterUrl = "https://image.tmdb.org/t/p/w500/poster.jpg",
            backdropUrl = "https://image.tmdb.org/t/p/w1280/backdrop.jpg",
            rating = 8.4,
            releaseYear = "2010",
            releaseDate = "2010-07-15",
            genres = "Action, Science Fiction",
            cast = "Leonardo DiCaprio",
            director = "Christopher Nolan",
            trailerYoutubeKey = "EN_TRAILER",
            trailerLanguage = "en",
            cachedAtMs = now
        )

        store1.putForMediaItem("vod_27205", isSeries = false, preferArabic = true, metadata = arMeta, nowMs = now)
        store1.putForMediaItem("vod_27205", isSeries = false, preferArabic = false, metadata = enMeta, nowMs = now)

        store1.putSeasonEpisodes(
            tmdbId = 1396,
            seasonNumber = 1,
            preferArabic = true,
            episodes = listOf(
                TmdbEpisodeMetadata(
                    episodeNumber = 1,
                    seasonNumber = 1,
                    title = "Pilot",
                    overview = "وصف الحلقة بالعربية",
                    stillUrl = "",
                    rating = 8.5,
                    durationSec = 3480,
                    airDate = "2008-01-20"
                )
            )
        )
        store1.putSeasonEpisodes(
            tmdbId = 1396,
            seasonNumber = 1,
            preferArabic = false,
            episodes = listOf(
                TmdbEpisodeMetadata(
                    episodeNumber = 1,
                    seasonNumber = 1,
                    title = "Pilot",
                    overview = "English episode overview",
                    stillUrl = "",
                    rating = 8.5,
                    durationSec = 3480,
                    airDate = "2008-01-20"
                )
            )
        )

        store1.putPersonBiography(
            personId = 6193,
            preferArabic = true,
            bio = TmdbPersonBiography(
                personId = 6193,
                name = "Leonardo DiCaprio",
                biography = "ممثل ومنتج أفلام أمريكي.",
                languageTag = "ar-SA",
                cachedAtMs = now
            )
        )
        store1.putPersonBiography(
            personId = 6193,
            preferArabic = false,
            bio = TmdbPersonBiography(
                personId = 6193,
                name = "Leonardo DiCaprio",
                biography = "American actor and film producer.",
                languageTag = "en-US",
                cachedAtMs = now
            )
        )

        // Reload fresh instance from disk to verify separate ar-SA and en-US persistence
        val store2 = TmdbMetadataCacheStore(context)
        val loadedAr = store2.getForMediaItem("vod_27205", 27205, isSeries = false, preferArabic = true)
        val loadedEn = store2.getForMediaItem("vod_27205", 27205, isSeries = false, preferArabic = false)
        assertNotNull(loadedAr)
        assertNotNull(loadedEn)
        assertEquals("Inception", loadedAr!!.title)
        assertTrue(loadedAr.overview.contains("مشاركة الأحلام"))
        assertEquals("ar", loadedAr.trailerLanguage)
        assertTrue(loadedEn!!.overview.contains("dream-sharing"))
        assertEquals("en", loadedEn.trailerLanguage)

        assertEquals("وصف الحلقة بالعربية", store2.getSeasonEpisodes(1396, 1, preferArabic = true).first().overview)
        assertEquals("English episode overview", store2.getSeasonEpisodes(1396, 1, preferArabic = false).first().overview)

        val loadedArBio = store2.getPersonBiography(6193, preferArabic = true)
        val loadedEnBio = store2.getPersonBiography(6193, preferArabic = false)
        assertEquals("Leonardo DiCaprio", loadedArBio?.name)
        assertTrue(loadedArBio?.biography.orEmpty().contains("ممثل ومنتج"))
        assertEquals("Leonardo DiCaprio", loadedEnBio?.name)
        assertTrue(loadedEnBio?.biography.orEmpty().contains("American actor"))
    }

    @Test
    fun localizeGenres_translatesEnglishGenresToArabicAndViceVersa() {
        val arabicGenres = TmdbMetadataMatcher.localizeGenres("Action, Science Fiction, Drama", preferArabic = true)
        assertTrue("Expected Arabic genres, got: $arabicGenres", arabicGenres.contains("أكشن") && arabicGenres.contains("خيال علمي") && arabicGenres.contains("دراما"))

        val englishGenres = TmdbMetadataMatcher.localizeGenres("أكشن، خيال علمي، دراما", preferArabic = false)
        assertTrue("Expected English genres, got: $englishGenres", englishGenres.contains("Action") && englishGenres.contains("Science Fiction") && englishGenres.contains("Drama"))
    }

    // =========================================================================
    // 6. Live Cloudflare Worker Proxy & TMDb Image CDN Verification
    // =========================================================================

    @Test
    fun liveApiVerification_checksProxyHealthMovieSearchTvSearchAndImageCdn() = runBlocking {
        val proxyUrl = TmdbProxyClient.CANONICAL_BACKEND_BASE_URL
        val client = TmdbProxyClient()

        val health = client.verifyConnection(proxyUrl)
        println("[LIVE_TMDB_CHECK] Proxy Health: isConnected=${health.isConnected}, http=${health.httpStatusCode}, state=${health.state}, msg=${TmdbErrorSanitizer.sanitize(health.message)}")

        if (health.httpStatusCode == 0) {
            println("[LIVE_TMDB_CHECK] External network egress unavailable in test container; skipping live assertions.")
            return@runBlocking
        }

        assertTrue("Expected live proxy HTTP 200 OK, got ${health.httpStatusCode}: ${health.message}", health.isConnected)

        val movieCandidates = client.searchCandidates(proxyUrl, "EN | Inception (2010) 4K", year = "2010", isSeries = false, preferArabic = false)
        println("[LIVE_TMDB_CHECK] Movie search candidates count=${movieCandidates.size}, top=${movieCandidates.firstOrNull()?.let { "${it.tmdbId}:${it.title}(${it.releaseYear}) score=${it.confidenceScore}" }}")
        assertTrue("Expected non-empty movie search candidates", movieCandidates.isNotEmpty())

        val movieMatch = client.fetchAccurateMediaMetadata(
            rawProxyUrl = proxyUrl,
            rawTitle = "EN | Inception (2010) 4K",
            year = "2010",
            isSeries = false,
            tmdbId = 0,
            preferArabic = false
        )
        println("[LIVE_TMDB_CHECK] Movie details: tmdbId=${movieMatch?.tmdbId}, title=${movieMatch?.title}, backdrop=${movieMatch?.backdropUrl}, overviewLen=${movieMatch?.overview?.length}, cast=${movieMatch?.cast?.take(60)}, castMembers=${movieMatch?.castMembers?.size}, director=${movieMatch?.director}, trailer=${movieMatch?.trailerYoutubeKey}, similar=${movieMatch?.similarItems?.size}")
        assertNotNull("Expected live movie metadata for Inception", movieMatch)
        assertEquals(27205, movieMatch!!.tmdbId)
        assertTrue("Expected non-empty movie backdrop", movieMatch.backdropUrl.startsWith("https://image.tmdb.org/"))
        assertTrue("Expected real movie overview", TmdbMetadataMatcher.isValidSynopsis(movieMatch.overview, "Inception"))

        val tvMatch = client.fetchAccurateMediaMetadata(
            rawProxyUrl = proxyUrl,
            rawTitle = "Breaking Bad (2008) FHD",
            year = "2008",
            isSeries = true,
            tmdbId = 0,
            preferArabic = false
        )
        println("[LIVE_TMDB_CHECK] TV details: tmdbId=${tvMatch?.tmdbId}, title=${tvMatch?.title}, backdrop=${tvMatch?.backdropUrl}, overviewLen=${tvMatch?.overview?.length}, seasons=${tvMatch?.seasons?.size}, director=${tvMatch?.director}")
        assertNotNull("Expected live TV metadata for Breaking Bad", tvMatch)
        assertEquals(1396, tvMatch!!.tmdbId)
        assertTrue("Expected non-empty TV backdrop", tvMatch.backdropUrl.startsWith("https://image.tmdb.org/"))

        val imageCheck = client.verifyImageUrl(movieMatch.backdropUrl)
        println("[LIVE_TMDB_CHECK] Image CDN check: status=${imageCheck.status}, http=${imageCheck.httpCode}, latency=${imageCheck.latencyMs}ms, summary=${imageCheck.summary}")
        assertEquals(TmdbDiagnosticStatus.PASSED, imageCheck.status)
    }

    private fun jsonResponse(request: Request, code: Int, body: String): Response {
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code in 200..299) "OK" else "Error")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
    }
}
