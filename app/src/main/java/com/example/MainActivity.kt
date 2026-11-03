package com.example

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.res.Configuration
import com.example.data.local.KrasRepository
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.player.PlayerImmersiveHelper
import com.example.ui.components.AppUpdateDialogs
import com.example.ui.components.BottomNavBar
import com.example.ui.components.NavScreen
import com.example.ui.screens.*
import com.example.ui.theme.*
import com.example.viewmodel.KrasViewModel

class MainActivity : ComponentActivity() {

    companion object {
        private var hasColdStarted = false
    }

    private val viewModel: KrasViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Restore any interrupted Do Not Disturb state from a previous session
        PlayerImmersiveHelper.restoreDnd(this, KrasRepository(this))

        val isColdStart = (savedInstanceState == null && !hasColdStarted)
        hasColdStarted = true

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
                val series by viewModel.series.collectAsState()
                val seriesCategories by viewModel.seriesCategories.collectAsState()
                val currentSeriesDetails by viewModel.currentSeriesDetails.collectAsState()
                val liveStreams by viewModel.liveStreams.collectAsState()
                val liveCategories by viewModel.liveCategories.collectAsState()
                val favorites by viewModel.favorites.collectAsState()
                val searchResults by viewModel.searchResults.collectAsState()
                val watchHistory by viewModel.watchHistory.collectAsState()
                val isRefreshing by viewModel.isRefreshing.collectAsState()
                val isLoading by viewModel.isLoading.collectAsState()

                val nowPlayingItem by viewModel.nowPlayingItem.collectAsState()
                val nowPlayingEpisode by viewModel.nowPlayingEpisode.collectAsState()
                val syncProgress by viewModel.syncProgress.collectAsState()
                val errorMessage by viewModel.errorMessage.collectAsState()
                val totalMoviesCount by viewModel.totalMoviesCount.collectAsState()
                val totalSeriesCount by viewModel.totalSeriesCount.collectAsState()
                val totalLiveCount by viewModel.totalLiveCount.collectAsState()
                val updateState by viewModel.updateState.collectAsState()

                // Play original cinematic intro sound on cold start launch only
                LaunchedEffect(Unit) {
                    if (isSplashActive) {
                        viewModel.playIntroSoundIfEnabled()
                    }
                }

                // Android Back Handling
                BackHandler {
                    when {
                        nowPlayingItem != null -> viewModel.stopPlayback()
                        selectedSeriesForDetails != null -> selectedSeriesForDetails = null
                        isSearchOpen -> isSearchOpen = false
                        isLoginOpen -> isLoginOpen = false
                        currentScreen != NavScreen.HOME -> currentScreen = NavScreen.HOME
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
                } else if (nowPlayingItem != null) {
                    // Fullscreen Cinema & Live Video Player
                    PlayerScreen(
                        mediaItem = nowPlayingItem!!,
                        episode = nowPlayingEpisode,
                        playerWrapper = viewModel.playerWrapper,
                        userSettings = userSettings,
                        onClose = { viewModel.stopPlayback() },
                        liveChannels = liveStreams,
                        onSwitchChannel = { channel ->
                            viewModel.startPlayback(channel)
                        },
                        onDownloadClick = {
                            viewModel.downloadItem(nowPlayingItem!!)
                        }
                    )
                } else if (isLoginOpen) {
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
                } else if (isSearchOpen) {
                    // Full Screen Global Search (Room-backed & LiveChannelOrder-sorted)
                    val allMedia = remember(searchResults, movies, series, liveStreams) {
                        if (searchResults.isNotEmpty()) searchResults else (movies + series + liveStreams)
                    }
                    SearchScreen(
                        allMedia = allMedia,
                        isFavorite = { id -> viewModel.isFavorite(id) },
                        onToggleFavorite = { item -> viewModel.toggleFavorite(item) },
                        onMediaClick = { item ->
                            if (item.type == MediaType.SERIES) {
                                viewModel.loadSeriesDetails(item)
                                selectedSeriesForDetails = item
                            } else {
                                viewModel.startPlayback(item)
                            }
                        },
                        onClose = { isSearchOpen = false },
                        liveChannelOrder = userSettings.liveChannelOrder,
                        onSearchQueryChanged = { q -> viewModel.performSearch(q) }
                    )
                } else {
                    // Main App Shell with Bottom Navigation
                    Scaffold(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(CinemaBlack),
                        containerColor = CinemaBlack,
                        bottomBar = {
                            BottomNavBar(
                                currentScreen = currentScreen,
                                onScreenSelected = { screen ->
                                    currentScreen = screen
                                }
                            )
                        }
                    ) { innerPadding ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(bottom = innerPadding.calculateBottomPadding())
                        ) {
                            when (currentScreen) {
                                NavScreen.HOME -> HomeScreen(
                                    movies = movies,
                                    series = series,
                                    liveStreams = liveStreams,
                                    watchHistory = watchHistory,
                                    isFavorite = { id -> viewModel.isFavorite(id) },
                                    onToggleFavorite = { item -> viewModel.toggleFavorite(item) },
                                    onMediaClick = { item ->
                                        if (item.type == MediaType.SERIES) {
                                            viewModel.loadSeriesDetails(item)
                                            selectedSeriesForDetails = item
                                        } else {
                                            viewModel.startPlayback(item)
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
                                    onLoadMore = { viewModel.loadMoreMovies() },
                                    onFilterChanged = { cat, q, rating, yr, qFilter, sortQ ->
                                        viewModel.filterMovies(cat, q, rating, yr, qFilter, sortQ)
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
                                    onLoadMore = { viewModel.loadMoreSeries() },
                                    onFilterChanged = { cat, q, rating, yr, qFilter, sortQ ->
                                        viewModel.filterSeries(cat, q, rating, yr, qFilter, sortQ)
                                    }
                                )

                                NavScreen.LIVE_TV -> LiveTvScreen(
                                    channels = liveStreams,
                                    categories = liveCategories,
                                    isFavorite = { id -> viewModel.isFavorite(id) },
                                    onToggleFavorite = { item -> viewModel.toggleFavorite(item) },
                                    onChannelClick = { channel -> viewModel.startPlayback(channel) },
                                    totalCount = totalLiveCount,
                                    liveChannelOrder = userSettings.liveChannelOrder,
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
                                    onCheckForUpdatesClick = { viewModel.checkForUpdates(isManual = true) }
                                )
                            }

                            // Non-blocking sync progress indicator
                            if (syncProgress != null) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(bottom = 16.dp)
                                        .clip(RoundedCornerShape(20.dp))
                                        .background(Color(0xE6151515))
                                        .border(1.dp, GlassBorder, RoundedCornerShape(20.dp))
                                        .padding(horizontal = 14.dp, vertical = 8.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        CircularProgressIndicator(
                                            color = KrasRed,
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
                        if (selectedSeriesForDetails != null) {
                            SeriesDetailsSheet(
                                seriesItem = selectedSeriesForDetails!!,
                                seriesDetails = currentSeriesDetails,
                                isLoading = isLoading,
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
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onResumeCheckPendingInstall()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && viewModel.nowPlayingItem.value != null) {
            PlayerImmersiveHelper.enterImmersiveMode(this)
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (!isInPictureInPictureMode && viewModel.nowPlayingItem.value != null) {
            PlayerImmersiveHelper.enterImmersiveMode(this)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (viewModel.nowPlayingItem.value != null) {
            PlayerImmersiveHelper.enterImmersiveMode(this)
        }
    }
}
