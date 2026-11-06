package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.example.BuildConfig
import com.example.data.model.Account
import com.example.data.model.AspectRatioMode
import com.example.data.model.CardStyle
import com.example.data.model.LiveChannelOrder
import com.example.data.model.SubscriptionInfo
import com.example.data.model.SubscriptionInfoHelper
import com.example.data.model.SubscriptionStatus
import com.example.data.model.TmdbAccountUiState
import com.example.data.model.TmdbAuthStatus
import com.example.data.model.TmdbSyncStatus
import com.example.data.model.UserSettings
import com.example.player.PlayerImmersiveHelper
import com.example.ui.components.KrasLogo
import com.example.ui.components.liquidGlassPanel
import com.example.ui.theme.*
import com.example.updater.AppUpdateUiState
import java.util.Locale
import kotlinx.coroutines.delay

@Composable
fun SettingsScreen(
    userSettings: UserSettings,
    onUpdateSettings: (UserSettings) -> Unit,
    activeAccount: Account?,
    accounts: List<Account>,
    onSwitchAccount: (String) -> Unit,
    onAddAccountClick: () -> Unit,
    onRemoveAccount: (String) -> Unit,
    onClearCache: () -> Unit,
    modifier: Modifier = Modifier,
    updateState: AppUpdateUiState = AppUpdateUiState(),
    onCheckForUpdatesClick: () -> Unit = {},
    onRefreshSubscriptionClick: () -> Unit = {},
    tmdbAccountState: TmdbAccountUiState = TmdbAccountUiState(),
    onConnectTmdbAccountClick: () -> Unit = {},
    onCompleteTmdbAuthClick: () -> Unit = {},
    onCancelTmdbAuthClick: () -> Unit = {},
    onSyncTmdbNowClick: () -> Unit = {},
    onToggleTmdbAutoSync: (Boolean) -> Unit = {},
    onDisconnectTmdbAccountClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var showPinDialog by remember { mutableStateOf(false) }
    var enteredPin by remember { mutableStateOf("") }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showDndPermissionDialog by remember { mutableStateOf(false) }
    var cacheClearedFeedback by remember { mutableStateOf(false) }

    // Live clock state for real-time countdown (days, hours, minutes)
    var currentTimeMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(activeAccount?.id) {
        onRefreshSubscriptionClick()
    }

    LaunchedEffect(activeAccount?.id, activeAccount?.expDate) {
        while (true) {
            currentTimeMs = System.currentTimeMillis()
            delay(15_000L)
        }
    }

    val isArabic = remember(userSettings.language) {
        SubscriptionInfoHelper.isArabicLanguage(userSettings.language)
    }

    val subscriptionInfo = remember(activeAccount, currentTimeMs) {
        SubscriptionInfoHelper.calculate(activeAccount, currentTimeMs)
    }

    // Re-check DND permission when returning from system settings screen
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                currentTimeMs = System.currentTimeMillis()
                onRefreshSubscriptionClick()
                if (PlayerImmersiveHelper.isDndPermissionGranted(context)) {
                    if (showDndPermissionDialog) {
                        showDndPermissionDialog = false
                        onUpdateSettings(userSettings.copy(silenceNotificationsWhileWatching = true))
                    }
                } else if (userSettings.silenceNotificationsWhileWatching) {
                    onUpdateSettings(userSettings.copy(silenceNotificationsWhileWatching = false))
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val accentColors = listOf(
        Pair("Kras Red", KrasRed),
        Pair("Neon Cyan", AccentCyan),
        Pair("Gold Amber", AccentGold),
        Pair("Emerald", AccentEmerald),
        Pair("Violet", AccentPurple)
    )

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(CinemaBlack)
            .statusBarsPadding()
            .padding(horizontal = 16.dp)
            .testTag("settings_screen")
    ) {
        item {
            Text(
                text = "Settings",
                color = Color.White,
                fontSize = 26.sp,
                fontFamily = OutfitFontFamily,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.padding(vertical = 14.dp)
            )
        }

        // 1. Account Management Card
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
                    .liquidGlassPanel(cornerRadius = 20.dp, accentGlow = KrasRed)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Active Subscription",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        TextButton(onClick = onAddAccountClick) {
                            Text("+ Add Account", color = KrasRedBright, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    if (activeAccount != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(KrasRed),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Person, contentDescription = "User", tint = Color.White)
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = activeAccount.name,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                                Text(
                                    text = "Status: ${subscriptionInfo.statusLabel(isArabic = false)} • Exp: ${subscriptionInfo.expirationDateText(isArabic = false)}",
                                    color = TextSecondary,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Switcher dropdown / items
                    if (accounts.size > 1) {
                        Text("Switch Account:", color = TextSecondary, fontSize = 11.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        accounts.forEach { acc ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSwitchAccount(acc.id) }
                                    .padding(vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = acc.name,
                                    color = if (acc.isActive) KrasRedBright else Color.White,
                                    fontWeight = if (acc.isActive) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 13.sp
                                )
                                if (acc.isActive) {
                                    Text("ACTIVE", color = KrasRedBright, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                } else {
                                    IconButton(
                                        onClick = { onRemoveAccount(acc.id) },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Gray, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 1b. Subscription Information & Remaining Duration Card (Supports English & Arabic)
        item {
            SubscriptionInformationCard(
                subscriptionInfo = subscriptionInfo,
                isArabic = isArabic,
                onRefreshClick = {
                    currentTimeMs = System.currentTimeMillis()
                    onRefreshSubscriptionClick()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            )
        }

        // 1c. TMDb Account & Sync Card (Official TMDb User Auth + Two-Way Sync)
        item {
            TmdbAccountSyncCard(
                tmdbAccountState = tmdbAccountState,
                isArabic = isArabic,
                onConnectClick = onConnectTmdbAccountClick,
                onCompleteAuthClick = onCompleteTmdbAuthClick,
                onCancelAuthClick = onCancelTmdbAuthClick,
                onSyncNowClick = onSyncTmdbNowClick,
                onToggleAutoSync = onToggleTmdbAutoSync,
                onDisconnectClick = onDisconnectTmdbAccountClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            )
        }

        // 2. Live TV Settings (Channel Order)
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
                    .liquidGlassPanel(cornerRadius = 20.dp)
                    .testTag("settings_live_tv_card")
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Live TV",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Text("Channel order", color = TextSecondary, fontSize = 12.sp)

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LiveChannelOrder.values().forEach { orderOption ->
                            val isSelected = userSettings.liveChannelOrder == orderOption
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isSelected) KrasRed.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.06f))
                                    .border(
                                        1.dp,
                                        if (isSelected) KrasRed else Color.Transparent,
                                        RoundedCornerShape(10.dp)
                                    )
                                    .clickable {
                                        onUpdateSettings(userSettings.copy(liveChannelOrder = orderOption))
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp)
                                    .testTag("channel_order_${orderOption.name.lowercase()}"),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = orderOption.label,
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    modifier = Modifier.weight(1f)
                                )
                                RadioButton(
                                    selected = isSelected,
                                    onClick = {
                                        onUpdateSettings(userSettings.copy(liveChannelOrder = orderOption))
                                    },
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = KrasRedBright,
                                        unselectedColor = TextSecondary
                                    )
                                )
                            }
                        }
                    }

                    HorizontalDivider(
                        color = Color.White.copy(alpha = 0.08f),
                        modifier = Modifier.padding(vertical = 4.dp)
                    )

                    // Background Playback Setting (Enabled / Disabled)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(
                                text = "Background Playback",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Continue Live TV audio and show Samsung/Android media notification controls when pressing Home or locking the screen",
                                color = TextSecondary,
                                fontSize = 11.sp
                            )
                        }
                        Switch(
                            checked = userSettings.backgroundPlayback,
                            onCheckedChange = { enabled ->
                                onUpdateSettings(userSettings.copy(backgroundPlayback = enabled))
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = KrasRed,
                                checkedTrackColor = KrasRedDark
                            ),
                            modifier = Modifier.testTag("background_playback_switch")
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(true to "Enabled", false to "Disabled").forEach { (enabledValue, label) ->
                            val isSelected = userSettings.backgroundPlayback == enabledValue
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isSelected) KrasRed else Color.White.copy(alpha = 0.08f))
                                    .border(
                                        width = 1.dp,
                                        color = if (isSelected) KrasRedBright else GlassBorder,
                                        shape = RoundedCornerShape(10.dp)
                                    )
                                    .clickable {
                                        onUpdateSettings(userSettings.copy(backgroundPlayback = enabledValue))
                                    }
                                    .padding(vertical = 10.dp)
                                    .testTag("background_playback_${label.lowercase()}"),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }
        }

        // 3. Theme & Customization
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
                    .liquidGlassPanel(cornerRadius = 20.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(
                        text = "Appearance & Theme",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )

                    // Accent Colors
                    Text("Accent Color", color = TextSecondary, fontSize = 12.sp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        accentColors.forEachIndexed { index, (name, color) ->
                            val isSelected = userSettings.accentColorIndex == index
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(color)
                                    .border(
                                        2.dp,
                                        if (isSelected) Color.White else Color.Transparent,
                                        CircleShape
                                    )
                                    .clickable {
                                        onUpdateSettings(userSettings.copy(accentColorIndex = index))
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                if (isSelected) {
                                    Icon(Icons.Default.Check, contentDescription = name, tint = Color.White, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }

                    // Card Layout Style
                    Text("Card Layout Style", color = TextSecondary, fontSize = 12.sp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(CardStyle.POSTER to "Poster", CardStyle.WIDE to "Wide", CardStyle.COMPACT to "Compact").forEach { (style, label) ->
                            val isSel = userSettings.cardStyle == style
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSel) KrasRed else Color.White.copy(alpha = 0.1f))
                                    .clickable { onUpdateSettings(userSettings.copy(cardStyle = style)) }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    // Intro sound toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Play intro sound", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text("Plays original cinematic boom/whoosh on launch", color = TextSecondary, fontSize = 11.sp)
                        }
                        Switch(
                            checked = userSettings.playIntroSound,
                            onCheckedChange = { onUpdateSettings(userSettings.copy(playIntroSound = it)) },
                            colors = SwitchDefaults.colors(checkedThumbColor = KrasRed, checkedTrackColor = KrasRedDark)
                        )
                    }
                }
            }
        }

        // 4. Playback & Streaming Engine
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
                    .liquidGlassPanel(cornerRadius = 20.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(
                        text = "Player & Network",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )

                    // Buffer Size
                    Text("Smart Buffer Size", color = TextSecondary, fontSize = 12.sp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("Standard", "Large", "Aggressive").forEach { buf ->
                            val isSel = userSettings.bufferSize == buf
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSel) KrasRed else Color.White.copy(alpha = 0.1f))
                                    .clickable { onUpdateSettings(userSettings.copy(bufferSize = buf)) }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(buf, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    // Default Aspect Ratio
                    Text("Default Aspect Ratio", color = TextSecondary, fontSize = 12.sp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            AspectRatioMode.FIT to "Fit",
                            AspectRatioMode.FILL to "Fill",
                            AspectRatioMode.ZOOM to "Zoom"
                        ).forEach { (mode, label) ->
                            val isSel = userSettings.defaultAspectRatio == mode
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSel) KrasRed else Color.White.copy(alpha = 0.1f))
                                    .clickable { onUpdateSettings(userSettings.copy(defaultAspectRatio = mode)) }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    // Silence notifications while watching (Do Not Disturb)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(
                                text = "Silence notifications while watching",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "Automatically turns on Do Not Disturb during playback and restores your previous setting when closing the player",
                                color = TextSecondary,
                                fontSize = 11.sp
                            )
                        }
                        Switch(
                            checked = userSettings.silenceNotificationsWhileWatching,
                            onCheckedChange = { enable ->
                                if (enable) {
                                    if (PlayerImmersiveHelper.isDndPermissionGranted(context)) {
                                        onUpdateSettings(userSettings.copy(silenceNotificationsWhileWatching = true))
                                    } else {
                                        showDndPermissionDialog = true
                                    }
                                } else {
                                    onUpdateSettings(userSettings.copy(silenceNotificationsWhileWatching = false))
                                }
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = KrasRed,
                                checkedTrackColor = KrasRedDark
                            )
                        )
                    }
                }
            }
        }

        // 4. Parental Controls & Security
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
                    .liquidGlassPanel(cornerRadius = 20.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Parental Control",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Lock Adult Content", color = Color.White, fontSize = 13.sp)
                            Text(
                                text = if (userSettings.parentalEnabled) "Protected by 4-digit PIN" else "Disabled",
                                color = TextSecondary,
                                fontSize = 11.sp
                            )
                        }
                        Switch(
                            checked = userSettings.parentalEnabled,
                            onCheckedChange = { showPinDialog = true },
                            colors = SwitchDefaults.colors(checkedThumbColor = KrasRed, checkedTrackColor = KrasRedDark)
                        )
                    }
                }
            }
        }

        // 5. Language, Cache & About
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp)
                    .liquidGlassPanel(cornerRadius = 20.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "System & Support",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )

                    // Language
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Language", color = Color.White, fontSize = 13.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("English", "العربية").forEach { lang ->
                                val isSel = userSettings.language == lang
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (isSel) KrasRed else Color.White.copy(alpha = 0.1f))
                                    .clickable { onUpdateSettings(userSettings.copy(language = lang)) }
                                        .padding(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text(lang, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    Divider(color = GlassBorder)

                    // Clear Cache
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onClearCache()
                                cacheClearedFeedback = true
                            }
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Clear Cache & Watch History", color = Color.White, fontSize = 13.sp)
                        Text(
                            text = if (cacheClearedFeedback) "Cleared!" else "Clear",
                            color = if (cacheClearedFeedback) AccentEmerald else KrasRedBright,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }

                    Divider(color = GlassBorder)

                    // Check for updates automatically toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(
                                text = "Check for updates automatically",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "Checks GitHub Releases in the background at most once every 6 hours",
                                color = TextSecondary,
                                fontSize = 11.sp
                            )
                        }
                        Switch(
                            checked = userSettings.autoCheckUpdates,
                            onCheckedChange = { enabled ->
                                onUpdateSettings(userSettings.copy(autoCheckUpdates = enabled))
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = KrasRed,
                                checkedTrackColor = KrasRedDark
                            ),
                            modifier = Modifier.testTag("settings_auto_check_updates_switch")
                        )
                    }

                    // Manual Check for updates button showing current version
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.White.copy(alpha = 0.06f))
                            .border(1.dp, GlassBorder, RoundedCornerShape(10.dp))
                            .clickable(enabled = !updateState.isChecking) {
                                onCheckForUpdatesClick()
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                            .testTag("settings_check_updates_button"),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Check for updates",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Current version: v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                                color = TextSecondary,
                                fontSize = 11.sp
                            )
                        }
                        if (updateState.isChecking) {
                            CircularProgressIndicator(
                                color = KrasRedBright,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(18.dp)
                            )
                        } else {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.SystemUpdate,
                                    contentDescription = "Check for updates",
                                    tint = KrasRedBright,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "Check",
                                    color = KrasRedBright,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    if (!updateState.statusMessage.isNullOrBlank()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 2.dp)
                                .testTag("settings_update_status_message"),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = updateState.statusMessage,
                                color = if (updateState.isStatusError) Color(0xFFFF8A80) else AccentEmerald,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f)
                            )
                            if (updateState.isStatusError && !updateState.isChecking) {
                                Text(
                                    text = "Retry",
                                    color = KrasRedBright,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .clickable { onCheckForUpdatesClick() }
                                        .padding(start = 8.dp)
                                        .testTag("settings_update_retry_button")
                                )
                            }
                        }
                    }

                    Divider(color = GlassBorder)

                    // About Kras TV
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showAboutDialog = true }
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("About Kras TV", color = Color.White, fontSize = 13.sp)
                        Icon(Icons.Default.Info, contentDescription = "About", tint = TextSecondary, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(80.dp))
        }
    }

    // Parental PIN Dialog
    if (showPinDialog) {
        AlertDialog(
            onDismissRequest = { showPinDialog = false },
            title = { Text("Parental PIN", color = Color.White) },
            text = {
                Column {
                    Text("Enter a 4-digit security PIN:", color = TextSecondary, fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = enteredPin,
                        onValueChange = { if (it.length <= 4) enteredPin = it },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = KrasRed,
                            unfocusedBorderColor = GlassBorder,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (enteredPin.length == 4) {
                            onUpdateSettings(
                                userSettings.copy(
                                    parentalEnabled = !userSettings.parentalEnabled,
                                    parentalPin = enteredPin
                                )
                            )
                            showPinDialog = false
                            enteredPin = ""
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = KrasRed)
                ) {
                    Text("Confirm", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showPinDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = CinemaDark
        )
    }

    // About Dialog
    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    KrasLogo(height = 24.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("v${BuildConfig.VERSION_NAME}", color = TextSecondary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Next-generation cinema and live IPTV streaming application.",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                    Text(
                        text = "• Xtream Codes API v2.0 Protocol Engine\n• Media3 ExoPlayer with HLS & TS Adaptive Streaming\n• Native 4K UHD and Dolby Audio Pipeline\n• Multi-Account Hot Switching",
                        color = Color.White,
                        fontSize = 11.sp,
                        lineHeight = 16.sp
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { showAboutDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = KrasRed)
                ) {
                    Text("Close", color = Color.White)
                }
            },
            containerColor = CinemaDark
        )
    }

    // Do Not Disturb Permission Explanatory Dialog
    if (showDndPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showDndPermissionDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.NotificationsOff,
                        contentDescription = "Do Not Disturb",
                        tint = KrasRedBright,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Do Not Disturb Permission",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "To automatically silence incoming calls, notifications, and banners while watching movies, series, or live TV, Kras TV needs permission to control Do Not Disturb mode.",
                        color = Color.White,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                    Text(
                        text = "Your previous notification settings will always be restored as soon as you exit the player. Tap \"Open Settings\" to grant permission in Android System Settings.",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        PlayerImmersiveHelper.openDndSettings(context)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = KrasRed)
                ) {
                    Text("Open Settings", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDndPermissionDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = CinemaDark,
            shape = RoundedCornerShape(16.dp)
        )
    }
}

@Composable
private fun SubscriptionInformationCard(
    subscriptionInfo: SubscriptionInfo,
    isArabic: Boolean,
    onRefreshClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val statusAccentColor = when (subscriptionInfo.status) {
        SubscriptionStatus.ACTIVE -> AccentEmerald
        SubscriptionStatus.EXPIRING_SOON -> AccentGold
        SubscriptionStatus.EXPIRED -> KrasRedBright
    }

    val statusIcon = when (subscriptionInfo.status) {
        SubscriptionStatus.ACTIVE -> Icons.Default.VerifiedUser
        SubscriptionStatus.EXPIRING_SOON -> Icons.Default.WarningAmber
        SubscriptionStatus.EXPIRED -> Icons.Default.ErrorOutline
    }

    val layoutDirection = if (isArabic) LayoutDirection.Rtl else LayoutDirection.Ltr

    CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
        Box(
            modifier = modifier
                .liquidGlassPanel(
                    cornerRadius = 20.dp,
                    accentGlow = when (subscriptionInfo.status) {
                        SubscriptionStatus.ACTIVE -> KrasRed
                        SubscriptionStatus.EXPIRING_SOON -> AccentGold
                        SubscriptionStatus.EXPIRED -> KrasRedBright
                    }
                )
                .testTag("subscription_info_card")
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 1. Header Row: Title + Status Badge + Refresh Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(KrasRed.copy(alpha = 0.18f))
                                .border(1.dp, KrasRedBright.copy(alpha = 0.45f), RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.CardMembership,
                                contentDescription = if (isArabic) "معلومات الاشتراك" else "Subscription Information",
                                tint = KrasRedBright,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        Column {
                            Text(
                                text = if (isArabic) "معلومات الاشتراك" else "Subscription Information",
                                color = Color.White,
                                fontSize = 15.sp,
                                fontFamily = OutfitFontFamily,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (isArabic) "حالة الحساب والمدة المتبقية" else "Account validity & remaining duration",
                                color = TextSecondary,
                                fontSize = 11.sp
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Subscription Status Pill (Active / Expiring Soon / Expired)
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(statusAccentColor.copy(alpha = 0.16f))
                                .border(1.dp, statusAccentColor.copy(alpha = 0.55f), RoundedCornerShape(50))
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                                .testTag("subscription_status_badge"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                imageVector = statusIcon,
                                contentDescription = subscriptionInfo.statusLabel(isArabic),
                                tint = statusAccentColor,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = subscriptionInfo.statusLabel(isArabic),
                                color = statusAccentColor,
                                fontSize = 11.sp,
                                fontFamily = OutfitFontFamily,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }

                        // Refresh Subscription Info Button
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.08f))
                                .border(1.dp, GlassBorder, CircleShape)
                                .clickable { onRefreshClick() }
                                .testTag("subscription_refresh_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = if (isArabic) "تحديث الاشتراك" else "Refresh Subscription",
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                // 2. Warning Banner (7 days or fewer remaining) or Expired Alert Banner
                val warningText = subscriptionInfo.warningText(isArabic)
                if ((subscriptionInfo.isExpiringWarningVisible || subscriptionInfo.isExpired) && !warningText.isNullOrBlank()) {
                    val bannerColor = if (subscriptionInfo.isExpired) KrasRedBright else AccentGold
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(bannerColor.copy(alpha = 0.14f))
                            .border(1.dp, bannerColor.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                            .testTag("subscription_warning_banner"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = if (subscriptionInfo.isExpired) Icons.Default.ErrorOutline else Icons.Default.WarningAmber,
                            contentDescription = if (isArabic) "تنبيه الاشتراك" else "Subscription Warning",
                            tint = bannerColor,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = warningText,
                            color = Color.White,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // 3. Activation Date & Expiration Date Side-by-Side Glass Tiles
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Activation Date Tile
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color.White.copy(alpha = 0.05f))
                            .border(1.dp, GlassBorder, RoundedCornerShape(14.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                            .testTag("subscription_activation_date_tile"),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.EventAvailable,
                                contentDescription = null,
                                tint = TextSecondary,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = if (isArabic) "تاريخ التفعيل" else "Activation Date",
                                color = TextSecondary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Text(
                            text = subscriptionInfo.activationDateText(isArabic),
                            color = if (subscriptionInfo.hasActivationDate) Color.White else TextSecondary,
                            fontSize = 14.sp,
                            fontFamily = OutfitFontFamily,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.testTag("subscription_activation_date_value")
                        )
                    }

                    // Expiration Date Tile
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color.White.copy(alpha = 0.05f))
                            .border(
                                1.dp,
                                if (subscriptionInfo.isExpiringWarningVisible || subscriptionInfo.isExpired) {
                                    statusAccentColor.copy(alpha = 0.45f)
                                } else {
                                    GlassBorder
                                },
                                RoundedCornerShape(14.dp)
                            )
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                            .testTag("subscription_expiration_date_tile"),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.EventBusy,
                                contentDescription = null,
                                tint = if (subscriptionInfo.isExpiringWarningVisible || subscriptionInfo.isExpired) statusAccentColor else TextSecondary,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = if (isArabic) "تاريخ الانتهاء" else "Expiration Date",
                                color = TextSecondary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Text(
                            text = subscriptionInfo.expirationDateText(isArabic),
                            color = when {
                                !subscriptionInfo.hasExpirationDate -> TextSecondary
                                subscriptionInfo.isExpired -> KrasRedBright
                                subscriptionInfo.isExpiringWarningVisible -> AccentGold
                                else -> Color.White
                            },
                            fontSize = 14.sp,
                            fontFamily = OutfitFontFamily,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.testTag("subscription_expiration_date_value")
                        )
                    }
                }

                // 4. Days Remaining Summary Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = 0.04f))
                        .border(1.dp, GlassBorder, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .testTag("subscription_remaining_days_row"),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Timelapse,
                            contentDescription = null,
                            tint = KrasRedBright,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = if (isArabic) "الأيام المتبقية" else "Days Remaining",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Text(
                        text = subscriptionInfo.remainingDaysText(isArabic),
                        color = when {
                            !subscriptionInfo.hasExpirationDate -> TextSecondary
                            subscriptionInfo.isExpired -> KrasRedBright
                            subscriptionInfo.isExpiringWarningVisible -> AccentGold
                            else -> AccentEmerald
                        },
                        fontSize = 13.sp,
                        fontFamily = OutfitFontFamily,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.testTag("subscription_remaining_days_value")
                    )
                }

                // 5. Live Countdown Section (Days, Hours, Minutes)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF0A0C12).copy(alpha = 0.72f))
                        .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
                        .padding(12.dp)
                        .testTag("subscription_live_countdown_section"),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(statusAccentColor)
                            )
                            Text(
                                text = if (isArabic) "العد التنازلي المباشر" else "Live Countdown",
                                color = TextSecondary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Text(
                            text = subscriptionInfo.countdownText(isArabic),
                            color = if (subscriptionInfo.hasExpirationDate) Color.White else TextSecondary,
                            fontSize = 11.sp,
                            fontFamily = OutfitFontFamily,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.testTag("subscription_live_countdown_text")
                        )
                    }

                    if (subscriptionInfo.hasExpirationDate) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CountdownUnitBox(
                                value = subscriptionInfo.countdownDays.toString(),
                                label = if (isArabic) "يوم" else "DAYS",
                                accentColor = statusAccentColor,
                                modifier = Modifier.weight(1f)
                            )
                            CountdownUnitBox(
                                value = String.format(Locale.US, "%02d", subscriptionInfo.countdownHours),
                                label = if (isArabic) "ساعة" else "HOURS",
                                accentColor = statusAccentColor,
                                modifier = Modifier.weight(1f)
                            )
                            CountdownUnitBox(
                                value = String.format(Locale.US, "%02d", subscriptionInfo.countdownMinutes),
                                label = if (isArabic) "دقيقة" else "MINUTES",
                                accentColor = statusAccentColor,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.White.copy(alpha = 0.04f))
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = subscriptionInfo.countdownText(isArabic),
                                color = TextSecondary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CountdownUnitBox(
    value: String,
    label: String,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, accentColor.copy(alpha = 0.28f), RoundedCornerShape(10.dp))
            .padding(vertical = 8.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = value,
            color = Color.White,
            fontSize = 18.sp,
            fontFamily = OutfitFontFamily,
            fontWeight = FontWeight.Black
        )
        Text(
            text = label,
            color = TextSecondary,
            fontSize = 9.sp,
            fontFamily = OutfitFontFamily,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.6.sp
        )
    }
}

@Composable
private fun TmdbAccountSyncCard(
    tmdbAccountState: TmdbAccountUiState,
    isArabic: Boolean,
    onConnectClick: () -> Unit,
    onCompleteAuthClick: () -> Unit,
    onCancelAuthClick: () -> Unit,
    onSyncNowClick: () -> Unit,
    onToggleAutoSync: (Boolean) -> Unit,
    onDisconnectClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val layoutDirection = if (isArabic) LayoutDirection.Rtl else LayoutDirection.Ltr
    val isConnected = tmdbAccountState.isConnected
    val isAuthorizing = tmdbAccountState.authStatus == TmdbAuthStatus.AUTHORIZING_IN_BROWSER
    val isVerifying = tmdbAccountState.authStatus == TmdbAuthStatus.VERIFYING_SESSION
    val isSyncing = tmdbAccountState.syncStatus == TmdbSyncStatus.SYNCING

    val badgeColor = when {
        isSyncing || isVerifying || isAuthorizing -> AccentGold
        isConnected -> AccentEmerald
        else -> TextSecondary
    }

    val badgeLabel = tmdbAccountState.statusBadgeLabel(isArabic)

    CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
        Box(
            modifier = modifier
                .liquidGlassPanel(
                    cornerRadius = 20.dp,
                    accentGlow = if (isConnected) AccentEmerald else KrasRed
                )
                .testTag("settings_tmdb_account_card")
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(KrasRed.copy(alpha = 0.18f))
                                .border(1.dp, KrasRedBright.copy(alpha = 0.45f), RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Sync,
                                contentDescription = if (isArabic) "حساب TMDb والمزامنة" else "TMDb Account & Sync",
                                tint = KrasRedBright,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                        Column {
                            Text(
                                text = if (isArabic) "حساب TMDb والمزامنة" else "TMDb Account & Sync",
                                color = Color.White,
                                fontSize = 15.sp,
                                fontFamily = OutfitFontFamily,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (isArabic) {
                                    "مزامنة ثنائية للمفضلة وقائمة المشاهدة والتقييمات"
                                } else {
                                    "Two-way sync for Favorites, Watchlist & Ratings"
                                },
                                color = TextSecondary,
                                fontSize = 11.sp
                            )
                        }
                    }

                    // Status Pill
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(badgeColor.copy(alpha = 0.16f))
                            .border(1.dp, badgeColor.copy(alpha = 0.55f), RoundedCornerShape(50))
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                            .testTag("tmdb_account_status_badge"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(badgeColor)
                        )
                        Text(
                            text = badgeLabel,
                            color = badgeColor,
                            fontSize = 11.sp,
                            fontFamily = OutfitFontFamily,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }

                if (isConnected) {
                    // Connected Account Profile Row (Avatar + Username + Synced Counts)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color.White.copy(alpha = 0.05f))
                            .border(1.dp, GlassBorder, RoundedCornerShape(14.dp))
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(46.dp)
                                    .clip(CircleShape)
                                    .background(KrasRed.copy(alpha = 0.25f))
                                    .border(1.5.dp, AccentEmerald.copy(alpha = 0.7f), CircleShape)
                                    .testTag("tmdb_account_avatar"),
                                contentAlignment = Alignment.Center
                            ) {
                                val initial = (tmdbAccountState.displayName.firstOrNull()
                                    ?: tmdbAccountState.username.firstOrNull()
                                    ?: 'T').uppercaseChar().toString()
                                Text(
                                    text = initial,
                                    color = Color.White,
                                    fontSize = 17.sp,
                                    fontFamily = OutfitFontFamily,
                                    fontWeight = FontWeight.ExtraBold
                                )
                                if (tmdbAccountState.avatarUrl.isNotBlank()) {
                                    AsyncImage(
                                        model = tmdbAccountState.avatarUrl,
                                        contentDescription = "TMDb Avatar",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clip(CircleShape)
                                    )
                                }
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = tmdbAccountState.displayName.ifBlank { tmdbAccountState.username },
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontFamily = OutfitFontFamily,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "@${tmdbAccountState.username}",
                                    color = AccentEmerald,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.testTag("tmdb_account_username")
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = if (isArabic) {
                                        "آخر مزامنة: ${tmdbAccountState.lastSyncFormatted(true)}"
                                    } else {
                                        "Last Synced: ${tmdbAccountState.lastSyncFormatted(false)}"
                                    },
                                    color = TextSecondary,
                                    fontSize = 11.sp,
                                    modifier = Modifier.testTag("tmdb_last_sync_text")
                                )
                            }
                        }
                    }

                    // Synced Stats Mini-Pills (Favorites, Watchlist, Ratings)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TmdbSyncCountPill(
                            label = if (isArabic) "المفضلة" else "Favorites",
                            count = tmdbAccountState.syncedFavoritesCount,
                            modifier = Modifier.weight(1f)
                        )
                        TmdbSyncCountPill(
                            label = if (isArabic) "قائمة المشاهدة" else "Watchlist",
                            count = tmdbAccountState.syncedWatchlistCount,
                            modifier = Modifier.weight(1f)
                        )
                        TmdbSyncCountPill(
                            label = if (isArabic) "التقييمات" else "Ratings",
                            count = tmdbAccountState.syncedRatingsCount,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    if (tmdbAccountState.pendingOfflineChangesCount > 0) {
                        Text(
                            text = if (isArabic) {
                                "${tmdbAccountState.pendingOfflineChangesCount} تغييرات محلية بانتظار المزامنة"
                            } else {
                                "${tmdbAccountState.pendingOfflineChangesCount} offline change(s) queued for sync"
                            },
                            color = AccentGold,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.testTag("tmdb_pending_offline_changes_text")
                        )
                    }

                    // Automatic Sync Toggle Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(
                                text = if (isArabic) "مزامنة تلقائية (Automatic Sync)" else "Automatic Sync",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = if (isArabic) {
                                    "مزامنة التغييرات تلقائياً في المفضلة وقائمة المشاهدة والتقييمات"
                                } else {
                                    "Automatically sync Favorites, Watchlist, and Ratings changes"
                                },
                                color = TextSecondary,
                                fontSize = 11.sp
                            )
                        }
                        Switch(
                            checked = tmdbAccountState.autoSyncEnabled,
                            onCheckedChange = onToggleAutoSync,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = KrasRed,
                                checkedTrackColor = KrasRedDark
                            ),
                            modifier = Modifier.testTag("tmdb_auto_sync_switch")
                        )
                    }

                    // Sync Now & Disconnect Account Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = onSyncNowClick,
                            enabled = !isSyncing,
                            colors = ButtonDefaults.buttonColors(containerColor = KrasRed),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("tmdb_sync_now_button")
                        ) {
                            if (isSyncing) {
                                CircularProgressIndicator(
                                    color = Color.White,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (isArabic) "جارٍ المزامنة..." else "Syncing...",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Sync,
                                    contentDescription = "Sync Now",
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isArabic) "مزامنة الآن" else "Sync Now",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        OutlinedButton(
                            onClick = onDisconnectClick,
                            enabled = !isSyncing,
                            border = androidx.compose.foundation.BorderStroke(1.dp, KrasRedBright.copy(alpha = 0.55f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = KrasRedBright),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("tmdb_logout_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Logout,
                                contentDescription = "Logout",
                                tint = KrasRedBright,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isArabic) "تسجيل الخروج" else "Logout",
                                color = KrasRedBright,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                } else {
                    // Disconnected / Authorizing View
                    Text(
                        text = if (isArabic) {
                            "سجل الدخول بحسابك في TMDb لمزامنة الأفلام والمسلسلات المفضلة وقائمة المشاهدة والتقييمات تلقائياً عبر جميع أجهزتك."
                        } else {
                            "Sign in with your personal TMDb account to access and sync your Favorites, Watchlist, and Ratings across all your devices."
                        },
                        color = TextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )

                    if (isAuthorizing) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = onCompleteAuthClick,
                                colors = ButtonDefaults.buttonColors(containerColor = AccentEmerald),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("tmdb_complete_auth_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = "Complete Sign-In",
                                    tint = Color.Black,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isArabic) "إكمال تسجيل الدخول" else "Complete Sign-In",
                                    color = Color.Black,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }

                            OutlinedButton(
                                onClick = onCancelAuthClick,
                                border = androidx.compose.foundation.BorderStroke(1.dp, GlassBorder),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                                modifier = Modifier.testTag("tmdb_cancel_auth_button")
                            ) {
                                Text(
                                    text = if (isArabic) "إلغاء" else "Cancel",
                                    color = TextSecondary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    } else {
                        Button(
                            onClick = onConnectClick,
                            enabled = !isVerifying,
                            colors = ButtonDefaults.buttonColors(containerColor = KrasRed),
                            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 11.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("tmdb_login_button")
                        ) {
                            if (isVerifying) {
                                CircularProgressIndicator(
                                    color = Color.White,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (isArabic) "جارٍ تسجيل الدخول إلى TMDb..." else "Logging in to TMDb...",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Login,
                                    contentDescription = "Login with TMDb",
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (isArabic) "تسجيل الدخول عبر TMDb" else "Login with TMDb",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                // Friendly non-technical status message (shown only when non-blank)
                val statusMsg = tmdbAccountState.statusMessage.orEmpty()
                if (statusMsg.isNotBlank()) {
                    val msgColor = when {
                        tmdbAccountState.isError || tmdbAccountState.syncStatus == TmdbSyncStatus.ERROR -> Color(0xFFFFB4AB)
                        tmdbAccountState.syncStatus == TmdbSyncStatus.SUCCESS -> AccentEmerald
                        else -> TextSecondary
                    }
                    Text(
                        text = statusMsg,
                        color = msgColor,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("tmdb_account_status_message")
                    )
                }

                // Official TMDb API Attribution Notice
                Text(
                    text = if (isArabic) {
                        "يستخدم هذا التطبيق واجهة برمجة تطبيقات TMDB ولكنه غير معتمد أو مصادق عليه من قِبل TMDB."
                    } else {
                        "This product uses the TMDB API but is not endorsed or certified by TMDB."
                    },
                    color = TextSecondary.copy(alpha = 0.7f),
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("tmdb_attribution_notice")
                )
            }
        }
    }
}

@Composable
private fun TmdbSyncCountPill(
    label: String,
    count: Int,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .border(1.dp, GlassBorder, RoundedCornerShape(10.dp))
            .padding(vertical = 8.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = count.toString(),
            color = Color.White,
            fontSize = 15.sp,
            fontFamily = OutfitFontFamily,
            fontWeight = FontWeight.ExtraBold
        )
        Text(
            text = label,
            color = TextSecondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
