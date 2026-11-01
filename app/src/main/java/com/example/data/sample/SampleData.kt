package com.example.data.sample

import com.example.data.model.*

object SampleData {

    val demoAccount = Account(
        id = "demo_account_id",
        name = "Kras Cinema Demo",
        serverUrl = "http://demo.krastv.com:8080",
        username = "guest_viewer",
        password = "••••••••",
        isActive = true,
        status = "Premium 4K",
        expDate = "2028-12-31",
        maxConnections = "5",
        isDemo = true
    )

    val liveCategories = listOf(
        Category("live_all", "All Channels", type = MediaType.LIVE),
        Category("live_news", "World News", type = MediaType.LIVE),
        Category("live_sports", "Live Sports", type = MediaType.LIVE),
        Category("live_movies", "Cinema & Movies", type = MediaType.LIVE),
        Category("live_docs", "Nature & Science", type = MediaType.LIVE),
        Category("live_music", "Music 24/7", type = MediaType.LIVE)
    )

    val movieCategories = listOf(
        Category("vod_all", "All Movies", type = MediaType.MOVIE),
        Category("vod_action", "Action & Sci-Fi", type = MediaType.MOVIE),
        Category("vod_drama", "Drama & Thriller", type = MediaType.MOVIE),
        Category("vod_animation", "Animation & Family", type = MediaType.MOVIE),
        Category("vod_comedy", "Comedy", type = MediaType.MOVIE),
        Category("vod_4k", "4K Ultra HD", type = MediaType.MOVIE)
    )

    val seriesCategories = listOf(
        Category("series_all", "All Series", type = MediaType.SERIES),
        Category("series_trending", "Trending Originals", type = MediaType.SERIES),
        Category("series_crime", "Crime & Mystery", type = MediaType.SERIES),
        Category("series_scifi", "Sci-Fi & Fantasy", type = MediaType.SERIES),
        Category("series_drama", "Prestige Drama", type = MediaType.SERIES)
    )

    // Real working public HLS and MP4 streams for robust testing
    const val STREAM_TEARS_OF_STEEL = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/TearsOfSteel.mp4"
    const val STREAM_BIG_BUCK_BUNNY = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"
    const val STREAM_SINTEL = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/Sintel.mp4"
    const val STREAM_ELEPHANTS_DREAM = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ElephantsDream.mp4"
    const val STREAM_SUBARU = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/SubaruOutbackOnStreetAndDirt.mp4"
    const val STREAM_FOR_BIGGER_BLAZES = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4"
    const val STREAM_NASA_HLS = "https://ntv1.akamaized.net/hls/live/2014075/NASA-NTV1-HLS/master.m3u8"
    const val STREAM_BLOOMBERG_HLS = "https://bloomberg.com/media-manifest/streams/us.m3u8"
    const val STREAM_ALJAZEERA_HLS = "https://live-hls-web-aje.getaj.net/AJE/01.m3u8"

    val sampleLiveStreams = listOf(
        MediaItem(
            id = "live_1",
            streamId = 101,
            name = "Kras Cinema 4K",
            title = "Kras Cinema 4K",
            type = MediaType.LIVE,
            streamIcon = "https://images.unsplash.com/photo-1489599849927-2ee91cede3ba?w=500&q=80",
            backdrop = "https://images.unsplash.com/photo-1517604931442-7e0c8ed2963c?w=1000&q=80",
            categoryId = "live_movies",
            categoryName = "Cinema & Movies",
            rating = 9.4,
            year = "LIVE",
            resolution = "4K UHD",
            quality = "HDR10",
            plot = "Non-stop blockbuster releases and award-winning international cinema in native 4K with Dolby Atmos.",
            streamUrl = STREAM_TEARS_OF_STEEL
        ),
        MediaItem(
            id = "live_2",
            streamId = 102,
            name = "Skyline World News",
            title = "Skyline World News",
            type = MediaType.LIVE,
            streamIcon = "https://images.unsplash.com/photo-1585829365295-ab7cd400c167?w=500&q=80",
            backdrop = "https://images.unsplash.com/photo-1504711434969-e33886168f5c?w=1000&q=80",
            categoryId = "live_news",
            categoryName = "World News",
            rating = 8.8,
            year = "LIVE",
            resolution = "1080p FHD",
            quality = "Live Feed",
            plot = "Live 24/7 breaking international reporting, financial markets, and geopolitical analysis.",
            streamUrl = STREAM_ALJAZEERA_HLS
        ),
        MediaItem(
            id = "live_3",
            streamId = 103,
            name = "Velocity Sports HD",
            title = "Velocity Sports HD",
            type = MediaType.LIVE,
            streamIcon = "https://images.unsplash.com/photo-1461896836934-ffe607ba8211?w=500&q=80",
            backdrop = "https://images.unsplash.com/photo-1508098682722-e99c43a406b2?w=1000&q=80",
            categoryId = "live_sports",
            categoryName = "Live Sports",
            rating = 9.1,
            year = "LIVE",
            resolution = "1080p 60FPS",
            quality = "Low Latency",
            plot = "Live motorsports, European football matches, basketball playoffs, and combat sports.",
            streamUrl = STREAM_FOR_BIGGER_BLAZES
        ),
        MediaItem(
            id = "live_4",
            streamId = 104,
            name = "Cosmos Discovery TV",
            title = "Cosmos Discovery TV",
            type = MediaType.LIVE,
            streamIcon = "https://images.unsplash.com/photo-1451187580459-43490279c0fa?w=500&q=80",
            backdrop = "https://images.unsplash.com/photo-1446776811953-b23d57bd21aa?w=1000&q=80",
            categoryId = "live_docs",
            categoryName = "Nature & Science",
            rating = 9.3,
            year = "LIVE",
            resolution = "4K UHD",
            quality = "Dolby Vision",
            plot = "Deep space telescope observations, aerospace missions, and breathtaking wildlife expeditions.",
            streamUrl = STREAM_NASA_HLS
        ),
        MediaItem(
            id = "live_5",
            streamId = 105,
            name = "Pulse Music Live",
            title = "Pulse Music Live",
            type = MediaType.LIVE,
            streamIcon = "https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=500&q=80",
            backdrop = "https://images.unsplash.com/photo-1470225620780-dba8ba36b745?w=1000&q=80",
            categoryId = "live_music",
            categoryName = "Music 24/7",
            rating = 8.5,
            year = "LIVE",
            resolution = "1080p FHD",
            quality = "Stereo Hi-Fi",
            plot = "Live DJ sets, global electronic music festivals, acoustic sessions, and top chart countdowns.",
            streamUrl = STREAM_SUBARU
        ),
        MediaItem(
            id = "live_6",
            streamId = 106,
            name = "Apex Action TV",
            title = "Apex Action TV",
            type = MediaType.LIVE,
            streamIcon = "https://images.unsplash.com/photo-1536440136628-849c177e76a1?w=500&q=80",
            backdrop = "https://images.unsplash.com/photo-1574375927938-d5a98e8ffe85?w=1000&q=80",
            categoryId = "live_movies",
            categoryName = "Cinema & Movies",
            rating = 8.9,
            year = "LIVE",
            resolution = "4K UHD",
            quality = "HDR",
            plot = "Martial arts marathons, tactical espionage thrillers, and explosive action sagas.",
            streamUrl = STREAM_BIG_BUCK_BUNNY
        )
    )

    val sampleMovies = listOf(
        MediaItem(
            id = "vod_1",
            streamId = 201,
            name = "Neon Horizon: 2099",
            title = "Neon Horizon: 2099",
            type = MediaType.MOVIE,
            streamIcon = "https://images.unsplash.com/photo-1578632767115-351597cf2477?w=500&q=80",
            backdrop = "https://images.unsplash.com/photo-1509198397868-475647b2a1e5?w=1000&q=80",
            categoryId = "vod_action",
            categoryName = "Action & Sci-Fi",
            rating = 9.2,
            year = "2025",
            resolution = "4K UHD",
            quality = "HDR10+",
            plot = "In a neon-drenched metropolis governed by synthetic intelligence, a rogue operative uncovers a conspiracy that threatens to overwrite human consciousness.",
            genre = "Cyberpunk / Sci-Fi / Action",
            duration = "2h 14m",
            streamUrl = STREAM_TEARS_OF_STEEL
        ),
        MediaItem(
            id = "vod_2",
            streamId = 202,
            name = "The Last Frontier",
            title = "The Last Frontier",
            type = MediaType.MOVIE,
            streamIcon = "https://images.unsplash.com/photo-1534447677768-be436bb09401?w=500&q=80",
            backdrop = "https://images.unsplash.com/photo-1518709268805-4e9042af9f23?w=1000&q=80",
            categoryId = "vod_action",
            categoryName = "Action & Sci-Fi",
            rating = 8.7,
            year = "2024",
            resolution = "4K UHD",
            quality = "Dolby Atmos",
            plot = "A solo astronaut on deep reconnaissance past Neptune discovers an enigmatic Dyson ring pulsating with unknown quantum signals.",
            genre = "Sci-Fi / Adventure",
            duration = "2h 31m",
            streamUrl = STREAM_SINTEL
        ),
        MediaItem(
            id = "vod_3",
            streamId = 203,
            name = "Shadows in the Mist",
            title = "Shadows in the Mist",
            type = MediaType.MOVIE,
            streamIcon = "https://images.unsplash.com/photo-1478760329108-5c3ed9d495a0?w=500&q=80",
            backdrop = "https://images.unsplash.com/photo-1518709268805-4e9042af9f23?w=1000&q=80",
            categoryId = "vod_drama",
            categoryName = "Drama & Thriller",
            rating = 8.9,
            year = "2024",
            resolution = "1080p FHD",
            quality = "HDR",
            plot = "A forensic psychologist retreats to an isolated coastal hamlet, only to find herself drawn into a cold case that echoes her own family tragedy.",
            genre = "Mystery / Psychological Thriller",
            duration = "1h 56m",
            streamUrl = STREAM_ELEPHANTS_DREAM
        ),
        MediaItem(
            id = "vod_4",
            streamId = 204,
            name = "Forest of Whispers",
            title = "Forest of Whispers",
            type = MediaType.MOVIE,
            streamIcon = "https://images.unsplash.com/photo-1511497584788-87676104235f?w=500&q=80",
            backdrop = "https://images.unsplash.com/photo-1448375240586-882707db888b?w=1000&q=80",
            categoryId = "vod_animation",
            categoryName = "Animation & Family",
            rating = 8.5,
            year = "2024",
            resolution = "4K UHD",
            quality = "HDR10",
            plot = "A spirited young alchemist journeys into an ancient enchanted woodland to restore the fading spirit tree and save her homeland.",
            genre = "Animation / Fantasy",
            duration = "1h 38m",
            streamUrl = STREAM_BIG_BUCK_BUNNY
        ),
        MediaItem(
            id = "vod_5",
            streamId = 205,
            name = "The Continental Vault",
            title = "The Continental Vault",
            type = MediaType.MOVIE,
            streamIcon = "https://images.unsplash.com/photo-1509281373149-e957c6296406?w=500&q=80",
            backdrop = "https://images.unsplash.com/photo-1486406146926-c627a92ad1ab?w=1000&q=80",
            categoryId = "vod_action",
            categoryName = "Action & Sci-Fi",
            rating = 8.4,
            year = "2023",
            resolution = "1080p FHD",
            quality = "Dolby 5.1",
            plot = "An international crew of master thieves attempts the impossible: breaching the subterranean gold depository beneath Zurich during an eclipse.",
            genre = "Heist / Thriller",
            duration = "2h 05m",
            streamUrl = STREAM_FOR_BIGGER_BLAZES
        ),
        MediaItem(
            id = "vod_6",
            streamId = 206,
            name = "Echoes of Eternity",
            title = "Echoes of Eternity",
            type = MediaType.MOVIE,
            streamIcon = "https://images.unsplash.com/photo-1447752875215-b2761acb3c5d?w=500&q=80",
            backdrop = "https://images.unsplash.com/photo-1470071459604-3b5ec3a7fe05?w=1000&q=80",
            categoryId = "vod_drama",
            categoryName = "Drama & Thriller",
            rating = 9.0,
            year = "2025",
            resolution = "4K UHD",
            quality = "Dolby Atmos",
            plot = "Two cellists separated by three centuries communicate through resonant frequencies discovered hidden in an antique Stradivarius.",
            genre = "Romance / Drama / Sci-Fi",
            duration = "2h 19m",
            streamUrl = STREAM_SUBARU
        )
    )

    val sampleSeries = listOf(
        MediaItem(
            id = "series_1",
            streamId = 301,
            name = "Kronos: The Temporal Protocol",
            title = "Kronos: The Temporal Protocol",
            type = MediaType.SERIES,
            streamIcon = "https://images.unsplash.com/photo-1509198397868-475647b2a1e5?w=500&q=80",
            backdrop = "https://images.unsplash.com/photo-1518709268805-4e9042af9f23?w=1000&q=80",
            categoryId = "series_trending",
            categoryName = "Trending Originals",
            rating = 9.5,
            year = "2024",
            resolution = "4K UHD",
            quality = "HDR10+",
            plot = "When temporal fractures begin appearing across major global cities, an elite task force must venture into rewritten timelines to prevent the unraveling of existence.",
            genre = "Sci-Fi / Thriller",
            duration = "3 Seasons"
        ),
        MediaItem(
            id = "series_2",
            streamId = 302,
            name = "Crimson Syndicate",
            title = "Crimson Syndicate",
            type = MediaType.SERIES,
            streamIcon = "https://images.unsplash.com/photo-1509281373149-e957c6296406?w=500&q=80",
            backdrop = "https://images.unsplash.com/photo-1486406146926-c627a92ad1ab?w=1000&q=80",
            categoryId = "series_crime",
            categoryName = "Crime & Mystery",
            rating = 8.9,
            year = "2023",
            resolution = "1080p FHD",
            quality = "Dolby Vision",
            plot = "A disgraced undercover detective navigates the brutal underworld of international arms trafficking while seeking redemption for his past mistakes.",
            genre = "Crime / Drama / Action",
            duration = "2 Seasons"
        ),
        MediaItem(
            id = "series_3",
            streamId = 303,
            name = "Valhalla Rising",
            title = "Valhalla Rising",
            type = MediaType.SERIES,
            streamIcon = "https://images.unsplash.com/photo-1464822759023-fed622ff2c3b?w=500&q=80",
            backdrop = "https://images.unsplash.com/photo-1506744038136-46273834b3fb?w=1000&q=80",
            categoryId = "series_drama",
            categoryName = "Prestige Drama",
            rating = 9.2,
            year = "2024",
            resolution = "4K UHD",
            quality = "Dolby Atmos",
            plot = "An epic saga chronicling the unification of the northern clans and their voyage across perilous uncharted seas toward an ancient mythical land.",
            genre = "Historical / Drama",
            duration = "4 Seasons"
        ),
        MediaItem(
            id = "series_4",
            streamId = 304,
            name = "Deep Orbit",
            title = "Deep Orbit",
            type = MediaType.SERIES,
            streamIcon = "https://images.unsplash.com/photo-1451187580459-43490279c0fa?w=500&q=80",
            backdrop = "https://images.unsplash.com/photo-1446776811953-b23d57bd21aa?w=1000&q=80",
            categoryId = "series_scifi",
            categoryName = "Sci-Fi & Fantasy",
            rating = 8.8,
            year = "2025",
            resolution = "4K UHD",
            quality = "HDR10",
            plot = "Scientists aboard a lunar research station intercept a dormant artificial probe that begins self-assembling using base moon regolith.",
            genre = "Sci-Fi / Mystery",
            duration = "1 Season"
        )
    )

    fun getSampleSeriesDetails(seriesId: Int): SeriesDetails {
        val series = sampleSeries.find { it.streamId == seriesId } ?: sampleSeries.first()
        return SeriesDetails(
            seriesId = series.streamId,
            name = series.name,
            cover = series.streamIcon,
            backdrop = series.backdrop,
            plot = series.plot,
            cast = "Alexander Vane, Elena Rostova, Marcus Thorne, Maya Lin",
            director = "Guillermo Del Croft",
            genre = series.genre,
            releaseDate = series.year,
            rating = series.rating,
            seasons = listOf(
                Season(
                    seasonNumber = 1,
                    name = "Season 1",
                    episodeCount = 4,
                    episodes = listOf(
                        Episode(
                            id = "ep_1_1",
                            episodeNum = 1,
                            title = "Pilot: Point of Origin",
                            containerExtension = "mp4",
                            plot = "The initial anomaly is detected in the low atmosphere, prompting a hasty dispatch of the first research recon unit.",
                            durationSec = 3120,
                            rating = 9.3,
                            streamUrl = STREAM_TEARS_OF_STEEL,
                            seriesId = series.streamId,
                            seasonNumber = 1
                        ),
                        Episode(
                            id = "ep_1_2",
                            episodeNum = 2,
                            title = "The Quantum Loophole",
                            containerExtension = "mp4",
                            plot = "Trapped in a localized temporal eddy, the crew must synchronize their biometric chronometers before reality collapses.",
                            durationSec = 2890,
                            rating = 9.1,
                            streamUrl = STREAM_SINTEL,
                            seriesId = series.streamId,
                            seasonNumber = 1
                        ),
                        Episode(
                            id = "ep_1_3",
                            episodeNum = 3,
                            title = "Subterranean Whispers",
                            containerExtension = "mp4",
                            plot = "Deep inside an abandoned decommissioned bunker, Elena finds an audio transcript dated thirty years in the future.",
                            durationSec = 3040,
                            rating = 8.9,
                            streamUrl = STREAM_ELEPHANTS_DREAM,
                            seriesId = series.streamId,
                            seasonNumber = 1
                        ),
                        Episode(
                            id = "ep_1_4",
                            episodeNum = 4,
                            title = "Season Finale: Threshold",
                            containerExtension = "mp4",
                            plot = "The final confrontation at the particle collider forces an impossible choice between preserving the past or safeguarding tomorrow.",
                            durationSec = 3450,
                            rating = 9.6,
                            streamUrl = STREAM_BIG_BUCK_BUNNY,
                            seriesId = series.streamId,
                            seasonNumber = 1
                        )
                    )
                ),
                Season(
                    seasonNumber = 2,
                    name = "Season 2",
                    episodeCount = 2,
                    episodes = listOf(
                        Episode(
                            id = "ep_2_1",
                            episodeNum = 1,
                            title = "Aftermath in Neon",
                            containerExtension = "mp4",
                            plot = "Six months after the temporal convergence, unexpected aftershocks ripple through alternate sector districts.",
                            durationSec = 2950,
                            rating = 9.0,
                            streamUrl = STREAM_FOR_BIGGER_BLAZES,
                            seriesId = series.streamId,
                            seasonNumber = 2
                        ),
                        Episode(
                            id = "ep_2_2",
                            episodeNum = 2,
                            title = "The Iron Covenant",
                            containerExtension = "mp4",
                            plot = "Old alliances fracture as a rival syndicate claims ownership of the stabilization generator.",
                            durationSec = 3100,
                            rating = 9.2,
                            streamUrl = STREAM_SUBARU,
                            seriesId = series.streamId,
                            seasonNumber = 2
                        )
                    )
                )
            )
        )
    }
}
