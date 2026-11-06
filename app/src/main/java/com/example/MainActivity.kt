package com.example

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.res.Configuration
import com.example.data.local.KrasRepository
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.model.TmdbAuthStatus
import com.example.player.PlayerImmersiveHelper
import com.example.ui.components.AppUpdateDialogs
import com.example.ui.components.BottomNavBar
import com.example.ui.components.NavScreen
import com.example.ui.components.liquidGlassPanel
import com.example.ui.screens.*
import com.example.ui.theme.*
import com.example.viewmodel.KrasViewModel

class MainActivity : ComponentActivity() {

    companion object {
        private var hasColdStarted = false
    }

    private val viewModel: KrasViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            splashScreen.setOnExitAnimationListener { splashScreenView ->
                splashScreenView.remove()
            }
        }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Restore any interrupted Do Not Disturb state from a previous session
        PlayerImmersiveHelper.restoreDnd(this, KrasRepository(this))

        val isColdStart = (savedInstanceState == null && !hasColdStarted)
        hasColdStarted = true

        intent?.data?.let { deepLinkUri ->
            viewModel.handleTmdbAuthCallback(deepLinkUri)
        }

        setContent {
            val userSettings by viewModel.userSettings.collectAsState()

            val accentColor = remember(userSettings.accentColorIndex) {
                when (userSettings.accentColorIndex) {
                    1 -> AccentCyan
                    2 -> AccentGold
                    3 -> AccentEmerald
                    4 -> AccentPurple
                    else -> KrasRed
                }
            }

            KrasTvTheme(accentColor = accentColor) {
                val context = LocalContext.current
                val crashPrefs = remember {
                    context.getSharedPreferences(KrasApplication.PREFS_CRASH, Context.MODE_PRIVATE)
                }
                var crashReportText by remember {
                    mutableStateOf(crashPrefs.getString(KrasApplication.KEY_CRASH_REPORT, null))
                }

                var isSplashActive by remember { mutableStateOf(isColdStart) }
                var currentScreen by remember { mutableStateOf(NavScreen.HOME) }
                var isSearchOpen by remember { mutableStateOf(false) }
                var isLoginOpen by remember { mutableStateOf(false) }
                var selectedSeriesForDetails by remember { mutableStateOf<MediaItem?>(null) }

                val activeAccount by viewModel.activeAccount.collectAsState()
                val accounts by viewModel.accounts.collectAsState()
                val movies by viewModel.movies.collectAsState()
                val movieCategories by viewModel.movieCategories.collectAsState()
                val latestMoviesForCategory by viewModel.latestMoviesForCategory.collectAsState()
                val latestMoviesByCategoryMap by viewModel.latestMoviesByCategoryMap.collectAsState()
                val availableMovieYears by viewModel.availableMovieYears.collectAsState()
                val series by viewModel.series.collectAsState()
                val seriesCategories by viewModel.seriesCategories.collectAsState()
                val latestSeriesForCategory by viewModel.latestSeriesForCategory.collectAsState()
                val latestSeriesByCategoryMap by viewModel.latestSeriesByCategoryMap.collectAsState()
                val availableSeriesYears by viewModel.availableSeriesYears.collectAsState()
                val currentSeriesDetails by viewModel.currentSeriesDetails.collectAsState()
                val isSeriesDetailsLoading by viewModel.isSeriesDetailsLoading.collectAsState()
                val liveStreams by viewModel.liveStreams.collectAsState()
                val liveCategories by viewModel.liveCategories.collectAsState()
                val activeLiveChannel by viewModel.activeLiveChannel.collectAsState()
                val isLiveFullscreen by viewModel.isLiveFullscreen.collectAsState()
                val liveFullscreenOrientation by viewModel.liveFullscreenOrientation.collectAsState()
                val isLiveMiniPlayerActive by viewModel.isLiveMiniPlayerActive.collectAsState()
                val recentLiveChannels by viewModel.recentLiveChannels.collectAsState()
                val lastWatchedLiveChannel by viewModel.lastWatchedLiveChannel.collectAsState()
                val favorites by viewModel.favorites.collectAsState()
                val searchResults by viewModel.searchResults.collectAsState()
                val watchHistory by viewModel.watchHistory.collectAsState()
                val isRefreshing by viewModel.isRefreshing.collectAsState()
                val isLoading by viewModel.isLoading.collectAsState()

                val configuration = LocalConfiguration.current
                val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                val isInPip = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    isInPictureInPictureMode
                } else {
                    false
                }

                val nowPlayingItem by viewModel.nowPlayingItem.collectAsState()
                val nowPlayingEpisode by viewModel.nowPlayingEpisode.collectAsState()
                var lastActivePlayingItem by remember { mutableStateOf<MediaItem?>(null) }
                var lastActivePlayingEpisode by remember { mutableStateOf< com.example.data.model.Episode?>(null) }
                if (nowPlayingItem != null) {
                    lastActivePlayingItem = nowPlayingItem
                    lastActivePlayingEpisode = nowPlayingEpisode
                }
                val syncProgress by viewModel.syncProgress.collectAsState()
                val errorMessage by viewModel.errorMessage.collectAsState()
                val totalMoviesCount by viewModel.totalMoviesCount.collectAsState()
                val totalSeriesCount by viewModel.totalSeriesCount.collectAsState()
                val totalLiveCount by viewModel.totalLiveCount.collectAsState()
                val updateState by viewModel.updateState.collectAsState()
                val tmdbAccountState by viewModel.tmdbAccountState.collectAsState()
                val navigateToSettingsFromAuth by viewModel.navigateToSettingsFromAuth.collectAsState()

                LaunchedEffect(navigateToSettingsFromAuth) {
                    if (navigateToSettingsFromAuth) {
                        isSearchOpen = false
                        isLoginOpen = false
                        selectedSeriesForDetails = null
                        currentScreen = NavScreen.SETTINGS
                        viewModel.consumeNavigateToSettingsFromAuth()
                    }
                }

                // Play original cinematic intro sound on cold start launch only
                LaunchedEffect(Unit) {
                    if (isSplashActive) {
                        viewModel.playIntroSoundIfEnabled()
                    }
                }

                // Lifecycle-aware Live TV Background Audio & One UI Media Notification Management
                val lifecycleOwner = LocalLifecycleOwner.current
                val currentBgPlayback by rememberUpdatedState(userSettings.backgroundPlayback)
                val currentActiveLive by rememberUpdatedState(activeLiveChannel)
                val currentNowPlaying by rememberUpdatedState(nowPlayingItem)
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        when (event) {
                            Lifecycle.Event.ON_RESUME -> {
                                if (tmdbAccountState.authStatus == TmdbAuthStatus.AUTHORIZING_IN_BROWSER) {
                                    viewModel.completeTmdbAuthorization(isAutoResumeCheck = true)
                                }
                                if (currentActiveLive != null && currentNowPlaying == null) {
                                    viewModel.playerWrapper.setBackgroundAudioOnlyMode(false)
                                    if (!currentBgPlayback) {
                                        viewModel.playerWrapper.resumeForLifecycle()
                                    }
                                }
                            }
                            Lifecycle.Event.ON_STOP -> {
                                val inPip = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                                    isInPictureInPictureMode
                                } else {
                                    false
                                }
                                if (!inPip && currentActiveLive != null && currentNowPlaying == null) {
                                    if (currentBgPlayback) {
                                        viewModel.playerWrapper.setBackgroundAudioOnlyMode(true)
                                    } else {
                                        viewModel.playerWrapper.pauseForLifecycle()
                                    }
                                }
                            }
                            else -> Unit
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose {
                        lifecycleOwner.lifecycle.removeObserver(observer)
                    }
                }

                // Android Back Handling
                BackHandler {
                    when {
                        nowPlayingItem != null -> viewModel.stopPlayback()
                        currentScreen == NavScreen.LIVE_TV && isLiveFullscreen -> viewModel.setLiveFullscreen(false)
                        selectedSeriesForDetails != null -> selectedSeriesForDetails = null
                        isSearchOpen -> isSearchOpen = false
                        isLoginOpen -> isLoginOpen = false
                        currentScreen != NavScreen.HOME -> {
                            if (currentScreen == NavScreen.LIVE_TV && activeLiveChannel != null) {
                                viewModel.setLiveMiniPlayer(true)
                            }
                            currentScreen = NavScreen.HOME
                        }
                        else -> finish()
                    }
                }

                // Global Crash Report Dialog (Shown if app closed unexpectedly in a previous session)
                if (crashReportText != null) {
                    AlertDialog(
                        onDismissRequest = {
                            crashPrefs.edit().remove(KrasApplication.KEY_CRASH_REPORT).apply()
                            crashReportText = null
                        },
                        title = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = "Crash Detected",
                                    tint = KrasRedBright,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Crash Report Detected",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp
                                )
                            }
                        },
                        text = {
                            Column {
                                Text(
                                    text = "Kras TV recovered from an unexpected error in the previous session. Diagnostic report:",
                                    color = TextSecondary,
                                    fontSize = 12.sp
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 240.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFF0F0F0F))
                                        .border(1.dp, GlassBorder, RoundedCornerShape(8.dp))
                                        .padding(10.dp)
                                        .verticalScroll(rememberScrollState())
                                ) {
                                    SelectionContainer {
                                        Text(
                                            text = crashReportText!!,
                                            color = Color(0xFFFFB4AB),
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.sp,
                                            lineHeight = 14.sp
                                        )
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("Kras TV Crash Report", crashReportText))
                                    Toast.makeText(context, "Crash log copied to clipboard", Toast.LENGTH_SHORT).show()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = KrasRed)
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Copy", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        },
                        dismissButton = {
                            TextButton(
                                onClick = {
                                    crashPrefs.edit().remove(KrasApplication.KEY_CRASH_REPORT).apply()
                                    crashReportText = null
                                }
                            ) {
                                Text("Dismiss", color = TextSecondary)
                            }
                        },
                        containerColor = CinemaDark
                    )
                }

                // In-App GitHub Release Update & Download Dialogs
                if (!isSplashActive) {
                    AppUpdateDialogs(
                        updateState = updateState,
                        onStartUpdate = { viewModel.startUpdateDownload() },
                        onDismissDialog = { viewModel.dismissUpdateDialog() },
                        onCancelDownload = { viewModel.cancelUpdateDownload() },
                        onRetryDownload = { viewModel.startUpdateDownload() },
                        onOpenInstallPermissionSettings = {
                            viewModel.openInstallPermissionSettings(context)
                        },
                        onDismissInstallPermissionDialog = {
                            viewModel.dismissInstallPermissionDialog()
                        }
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                ) {
                    if (isLoginOpen && !isSplashActive) {
                        // Login / Account Manager Screen
                        LoginScreen(
                            onLoginSuccess = { isLoginOpen = false },
                            savedAccounts = accounts,
                            onSelectAccount = { acc ->
                                viewModel.switchAccount(acc.id)
                                isLoginOpen = false
                            },
                            onDemoLogin = {
                                viewModel.loginDemoMode()
                                isLoginOpen = false
                            },
                            onPerformLogin = { serverUrl, user, pass, callback ->
                                viewModel.login(serverUrl, user, pass, callback)
                            }
                        )
                    } else if (isSearchOpen && !isSplashActive) {
                    // Full Screen Global Search (Room-backed & LiveChannelOrder-sorted)
                    val allMedia = remember(searchResults, movies, series, liveStreams) {
                        if (searchResults.isNotEmpty()) searchResults else (movies + series + liveStreams)
                    }
                    SearchScreen(
                        allMedia = allMedia,
                        isFavorite = { id -> viewModel.isFavorite(id) },
                        onToggleFavorite = { item -> viewModel.toggleFavorite(item) },
                        onMediaClick = { item ->
                            when (item.type) {
                                MediaType.SERIES -> {
                                    viewModel.loadSeriesDetails(item)
                                    selectedSeriesForDetails = item
                                }
                                MediaType.LIVE -> {
                                    isSearchOpen = false
                                    currentScreen = NavScreen.LIVE_TV
                                    viewModel.selectLiveChannel(item)
                                }
                                MediaType.MOVIE -> {
                                    viewModel.startPlayback(item)
                                }
                            }
                        },
                        onClose = { isSearchOpen = false },
                        liveChannelOrder = userSettings.liveChannelOrder,
                        onSearchQueryChanged = { q -> viewModel.performSearch(q) }
                    )
                } else {
                    // Main App Shell with Floating Liquid Glass Bottom Navigation
                    Scaffold(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(CinemaBlack),
                        containerColor = CinemaBlack,
                        contentWindowInsets = WindowInsets(0, 0, 0, 0),
                        bottomBar = {
                            val hideBottomBar = isInPip || (currentScreen == NavScreen.LIVE_TV && (isLiveFullscreen || isLandscape))
                            if (!hideBottomBar) {
                                BottomNavBar(
                                    currentScreen = currentScreen,
                                    onScreenSelected = { screen ->
                                        if (currentScreen == NavScreen.LIVE_TV && screen != NavScreen.LIVE_TV && activeLiveChannel != null) {
                                            viewModel.setLiveMiniPlayer(true)
                                        } else if (screen == NavScreen.LIVE_TV) {
                                            viewModel.setLiveMiniPlayer(false)
                                        }
                                        currentScreen = screen
                                    }
                                )
                            }
                        }
                    ) { innerPadding ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = innerPadding.calculateTopPadding())
                        ) {
                            AnimatedContent(
                                targetState = currentScreen,
                                transitionSpec = {
                                    val direction = if (targetState.ordinal >= initialState.ordinal) 1 else -1
                                    (fadeIn(animationSpec = tween(240, easing = FastOutSlowInEasing)) +
                                        slideInHorizontally(
                                            animationSpec = tween(260, easing = FastOutSlowInEasing),
                                            initialOffsetX = { fullWidth -> (fullWidth * 0.06f * direction).toInt() }
                                        ) +
                                        scaleIn(
                                            initialScale = 0.98f,
                                            animationSpec = tween(240, easing = FastOutSlowInEasing)
                                        )).togetherWith(
                                        fadeOut(animationSpec = tween(180, easing = FastOutSlowInEasing)) +
                                            slideOutHorizontally(
                                                animationSpec = tween(220, easing = FastOutSlowInEasing),
                                                targetOffsetX = { fullWidth -> (-fullWidth * 0.04f * direction).toInt() }
                                            )
                                    )
                                },
                                label = "nav_screen_transition"
                            ) { targetScreen ->
                                when (targetScreen) {
                                    NavScreen.HOME -> HomeScreen(
                                        movies = movies,
                                        series = series,
                                        liveStreams = liveStreams,
                                        watchHistory = watchHistory,
                                        isFavorite = { id -> viewModel.isFavorite(id) },
                                        onToggleFavorite = { item -> viewModel.toggleFavorite(item) },
                                        onMediaClick = { item ->
                                            when (item.type) {
                                                MediaType.SERIES -> {
                                                    viewModel.loadSeriesDetails(item)
                                                    selectedSeriesForDetails = item
                                                }
                                                MediaType.LIVE -> {
                                                    currentScreen = NavScreen.LIVE_TV
                                                    viewModel.selectLiveChannel(item)
                                                }
                                                MediaType.MOVIE -> {
                                                    viewModel.startPlayback(item)
                                                }
                                            }
                                        },
                                        onSearchClick = { isSearchOpen = true },
                                        onAccountClick = { isLoginOpen = true },
                                        onRefreshClick = { viewModel.refreshContent() },
                                        isRefreshing = isRefreshing,
                                        isLoading = isLoading,
                                        cardStyle = userSettings.cardStyle,
                                        favorites = favorites
                                    )

                                    NavScreen.MOVIES -> MoviesScreen(
                                        movies = movies,
                                        categories = movieCategories,
                                        watchHistory = watchHistory,
                                        isFavorite = { id -> viewModel.isFavorite(id) },
                                        onToggleFavorite = { item -> viewModel.toggleFavorite(item) },
                                        onMovieClick = { movie -> viewModel.startPlayback(movie) },
                                        cardStyle = userSettings.cardStyle,
                                        totalCount = totalMoviesCount,
                                        latestMoviesForCategory = latestMoviesForCategory,
                                        latestMoviesByCategoryMap = latestMoviesByCategoryMap,
                                        availableYears = availableMovieYears,
                                        onLoadMore = { viewModel.loadMoreMovies() },
                                        onFilterChanged = { cat, q, rating, yr, qFilter, sortQ, sortOrder ->
                                            viewModel.filterMovies(cat, q, rating, yr, qFilter, sortQ, sortOrder)
                                        }
                                    )

                                    NavScreen.SERIES -> SeriesScreen(
                                        seriesList = series,
                                        categories = seriesCategories,
                                        isFavorite = { id -> viewModel.isFavorite(id) },
                                        onToggleFavorite = { item -> viewModel.toggleFavorite(item) },
                                        onSeriesClick = { s ->
                                            viewModel.loadSeriesDetails(s)
                                            selectedSeriesForDetails = s
                                        },
                                        cardStyle = userSettings.cardStyle,
                                        totalCount = totalSeriesCount,
                                        latestSeriesForCategory = latestSeriesForCategory,
                                        latestSeriesByCategoryMap = latestSeriesByCategoryMap,
                                        availableYears = availableSeriesYears,
                                        watchHistory = watchHistory,
                                        onLoadMore = { viewModel.loadMoreSeries() },
                                        onFilterChanged = { cat, q, rating, yr, qFilter, sortQ, sortOrder ->
                                            viewModel.filterSeries(cat, q, rating, yr, qFilter, sortQ, sortOrder)
                                        }
                                    )

                                    NavScreen.LIVE_TV -> LiveTvScreen(
                                        channels = liveStreams,
                                        categories = liveCategories,
                                        isFavorite = { id -> viewModel.isFavorite(id) },
                                        onToggleFavorite = { item -> viewModel.toggleFavorite(item) },
                                        onChannelClick = { channel -> viewModel.selectLiveChannel(channel) },
                                        totalCount = totalLiveCount,
                                        liveChannelOrder = userSettings.liveChannelOrder,
                                        activeChannel = activeLiveChannel,
                                        playerWrapper = viewModel.playerWrapper,
                                        isFullscreen = isLiveFullscreen,
                                        onToggleFullscreen = { fullscreen -> viewModel.setLiveFullscreen(fullscreen) },
                                        fullscreenOrientation = liveFullscreenOrientation,
                                        onSelectFullscreenOrientation = { orientation ->
                                            viewModel.setLiveFullscreenOrientation(orientation)
                                        },
                                        onRetryPlayback = { viewModel.retryLiveChannel() },
                                        onStopPlayback = { viewModel.stopLiveChannelPlayback() },
                                        defaultAspectRatio = userSettings.defaultAspectRatio,
                                        recentChannels = recentLiveChannels,
                                        lastWatchedChannel = lastWatchedLiveChannel,
                                        swipeGesturesEnabled = userSettings.liveSwipeGesturesEnabled,
                                        onToggleSwipeGestures = { enabled ->
                                            viewModel.updateSettings(userSettings.copy(liveSwipeGesturesEnabled = enabled))
                                        },
                                        backgroundPlaybackEnabled = userSettings.backgroundPlayback,
                                        onToggleBackgroundPlayback = { enabled ->
                                            viewModel.updateSettings(userSettings.copy(backgroundPlayback = enabled))
                                        },
                                        onMinimizeToMiniPlayer = {
                                            viewModel.setLiveMiniPlayer(true)
                                            currentScreen = NavScreen.HOME
                                        },
                                        onEnterPip = {
                                            PlayerImmersiveHelper.enterPictureInPicture(this@MainActivity)
                                        },
                                        onLoadMore = { viewModel.loadMoreLive() },
                                        onFilterChanged = { cat, q, qFilter, sortQ ->
                                            viewModel.filterLive(cat, q, qFilter, sortQ)
                                        }
                                    )

                                    NavScreen.SETTINGS -> SettingsScreen(
                                        userSettings = userSettings,
                                        onUpdateSettings = { newSettings -> viewModel.updateSettings(newSettings) },
                                        activeAccount = activeAccount,
                                        accounts = accounts,
                                        onSwitchAccount = { accId -> viewModel.switchAccount(accId) },
                                        onAddAccountClick = { isLoginOpen = true },
                                        onRemoveAccount = { accId -> viewModel.removeAccount(accId) },
                                        onClearCache = { viewModel.clearCache() },
                                        updateState = updateState,
                                        onCheckForUpdatesClick = { viewModel.checkForUpdates(isManual = true) },
                                        onRefreshSubscriptionClick = { viewModel.refreshSubscriptionInfo() },
                                        tmdbAccountState = tmdbAccountState,
                                        onConnectTmdbAccountClick = {
                                            viewModel.startTmdbAccountAuthorization { authUrl ->
                                                try {
                                                    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)).apply {
                                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                    }
                                                    context.startActivity(browserIntent)
                                                } catch (_: Exception) {
                                                    Toast.makeText(
                                                        context,
                                                        "Unable to open browser for TMDb authorization.",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                            }
                                        },
                                        onCompleteTmdbAuthClick = {
                                            viewModel.completeTmdbAuthorization(isAutoResumeCheck = false)
                                        },
                                        onCancelTmdbAuthClick = {
                                            viewModel.cancelTmdbAuthorization()
                                        },
                                        onSyncTmdbNowClick = {
                                            viewModel.syncTmdbAccountNow(isAuto = false)
                                        },
                                        onToggleTmdbAutoSync = { enabled ->
                                            viewModel.setTmdbAutoSyncEnabled(enabled)
                                        },
                                        onDisconnectTmdbAccountClick = {
                                            viewModel.disconnectTmdbAccount()
                                        }
                                    )
                                }
                            }

                            // Floating In-App Live TV Mini Player (shown when browsing other tabs while a live channel is playing)
                            val showMiniPlayer = !isInPip &&
                                    nowPlayingItem == null &&
                                    activeLiveChannel != null &&
                                    (currentScreen != NavScreen.LIVE_TV || isLiveMiniPlayerActive)
                            if (showMiniPlayer && activeLiveChannel != null) {
                                LiveMiniPlayerOverlay(
                                    channel = activeLiveChannel!!,
                                    playerWrapper = viewModel.playerWrapper,
                                    onExpandClick = {
                                        viewModel.setLiveMiniPlayer(false)
                                        currentScreen = NavScreen.LIVE_TV
                                    },
                                    onNextChannelClick = {
                                        viewModel.switchLiveChannelByOffset(1)
                                    },
                                    onCloseClick = {
                                        viewModel.stopLiveChannelPlayback()
                                    },
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .navigationBarsPadding()
                                        .padding(bottom = 86.dp)
                                )
                            }

                            // Non-blocking Liquid Glass sync progress indicator
                            if (syncProgress != null) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(bottom = 92.dp)
                                        .liquidGlassPanel(cornerRadius = 22.dp, accentGlow = KrasRed)
                                        .padding(horizontal = 16.dp, vertical = 9.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        CircularProgressIndicator(
                                            color = KrasRedBright,
                                            strokeWidth = 2.dp,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = syncProgress!!,
                                            color = Color.White,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }

                            // Friendly Error banner with Retry
                            if (errorMessage != null) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopCenter)
                                        .statusBarsPadding()
                                        .padding(top = 10.dp, start = 16.dp, end = 16.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color(0xF0200808))
                                        .border(1.dp, KrasRed.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                        .padding(horizontal = 14.dp, vertical = 10.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Warning,
                                                contentDescription = "Error",
                                                tint = KrasRedBright,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = errorMessage!!,
                                                color = Color.White,
                                                fontSize = 12.sp,
                                                maxLines = 2
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        TextButton(
                                            onClick = {
                                                viewModel.clearErrorMessage()
                                                viewModel.refreshContent()
                                            },
                                            colors = ButtonDefaults.textButtonColors(contentColor = KrasRedBright)
                                        ) {
                                            Text("Retry", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                        }

                        // Series Details Bottom Sheet
                        if (selectedSeriesForDetails != null && nowPlayingItem == null) {
                            SeriesDetailsSheet(
                                seriesItem = selectedSeriesForDetails!!,
                                seriesDetails = currentSeriesDetails,
                                isLoading = isSeriesDetailsLoading,
                                onDismiss = { selectedSeriesForDetails = null },
                                onPlayEpisode = { seriesItem, episode ->
                                    selectedSeriesForDetails = null
                                    viewModel.startPlayback(seriesItem, episode)
                                },
                                onDownloadEpisode = { seriesItem, episode ->
                                    viewModel.downloadItem(
                                        seriesItem.copy(
                                            id = episode.id,
                                            name = "${seriesItem.name} - ${episode.title}",
                                            streamUrl = episode.streamUrl
                                        )
                                    )
                                },
                                isFavorite = viewModel.isFavorite(selectedSeriesForDetails!!.id),
                                onToggleFavorite = { viewModel.toggleFavorite(selectedSeriesForDetails!!) },
                                watchHistory = watchHistory
                            )
                        }
                    }
                    }

                    // Unified Seamless Fullscreen Video Player Overlay
                    androidx.compose.animation.AnimatedVisibility(
                        visible = !isSplashActive && nowPlayingItem != null,
                        enter = fadeIn(animationSpec = tween(220, easing = FastOutSlowInEasing)),
                        exit = fadeOut(animationSpec = tween(180, easing = FastOutSlowInEasing)),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        val activeMedia = nowPlayingItem ?: lastActivePlayingItem
                        val activeEpisode = if (nowPlayingItem != null) nowPlayingEpisode else lastActivePlayingEpisode
                        if (activeMedia != null) {
                            PlayerScreen(
                                mediaItem = activeMedia,
                                episode = activeEpisode,
                                playerWrapper = viewModel.playerWrapper,
                                userSettings = userSettings,
                                onClose = { viewModel.stopPlayback() },
                                liveChannels = liveStreams,
                                onSwitchChannel = { channel ->
                                    viewModel.startPlayback(channel)
                                },
                                onDownloadClick = {
                                    viewModel.downloadItem(activeMedia)
                                },
                                seriesDetails = currentSeriesDetails,
                                moviesPlaylist = movies,
                                onPlayEpisode = { ep ->
                                    viewModel.startPlayback(activeMedia, ep)
                                },
                                onRetry = {
                                    viewModel.retryPlayback()
                                }
                            )
                        }
                    }

                    // Cinematic 3D Glass KRAS TV Splash Overlay (preloads HomeScreen underneath)
                    if (isSplashActive) {
                        SplashScreen(
                            isLoading = isLoading,
                            onSplashFinished = {
                                viewModel.stopIntroSound()
                                isSplashActive = false
                                if (activeAccount == null) {
                                    isLoginOpen = true
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.data?.let { deepLinkUri ->
            viewModel.handleTmdbAuthCallback(deepLinkUri)
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onResumeCheckPendingInstall()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && (viewModel.nowPlayingItem.value != null || viewModel.isLiveFullscreen.value)) {
            PlayerImmersiveHelper.enterImmersiveMode(this)
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (!isInPictureInPictureMode && (viewModel.nowPlayingItem.value != null || viewModel.isLiveFullscreen.value)) {
            PlayerImmersiveHelper.enterImmersiveMode(this)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (viewModel.nowPlayingItem.value != null || viewModel.isLiveFullscreen.value) {
            PlayerImmersiveHelper.enterImmersiveMode(this)
        }
    }
}

