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
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.ui.components.BottomNavBar
import com.example.ui.components.NavScreen
import com.example.ui.screens.*
import com.example.ui.theme.*
import com.example.viewmodel.KrasViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: KrasViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

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

                var isSplashActive by remember { mutableStateOf(true) }
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
                val watchHistory by viewModel.watchHistory.collectAsState()
                val isRefreshing by viewModel.isRefreshing.collectAsState()
                val isLoading by viewModel.isLoading.collectAsState()

                val nowPlayingItem by viewModel.nowPlayingItem.collectAsState()
                val nowPlayingEpisode by viewModel.nowPlayingEpisode.collectAsState()

                // Play original cinematic intro sound on launch
                LaunchedEffect(Unit) {
                    viewModel.playIntroSoundIfEnabled()
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

                if (isSplashActive) {
                    SplashScreen(
                        onSplashFinished = {
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
                    // Full Screen Global Search
                    val allMedia = remember(movies, series, liveStreams) {
                        movies + series + liveStreams
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
                        onClose = { isSearchOpen = false }
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
                                    cardStyle = userSettings.cardStyle
                                )

                                NavScreen.MOVIES -> MoviesScreen(
                                    movies = movies,
                                    categories = movieCategories,
                                    watchHistory = watchHistory,
                                    isFavorite = { id -> viewModel.isFavorite(id) },
                                    onToggleFavorite = { item -> viewModel.toggleFavorite(item) },
                                    onMovieClick = { movie -> viewModel.startPlayback(movie) },
                                    cardStyle = userSettings.cardStyle
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
                                    cardStyle = userSettings.cardStyle
                                )

                                NavScreen.LIVE_TV -> LiveTvScreen(
                                    channels = liveStreams,
                                    categories = liveCategories,
                                    isFavorite = { id -> viewModel.isFavorite(id) },
                                    onToggleFavorite = { item -> viewModel.toggleFavorite(item) },
                                    onChannelClick = { channel -> viewModel.startPlayback(channel) }
                                )

                                NavScreen.SETTINGS -> SettingsScreen(
                                    userSettings = userSettings,
                                    onUpdateSettings = { newSettings -> viewModel.updateSettings(newSettings) },
                                    activeAccount = activeAccount,
                                    accounts = accounts,
                                    onSwitchAccount = { accId -> viewModel.switchAccount(accId) },
                                    onAddAccountClick = { isLoginOpen = true },
                                    onRemoveAccount = { accId -> viewModel.removeAccount(accId) },
                                    onClearCache = { viewModel.clearCache() }
                                )
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
}
