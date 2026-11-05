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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.BuildConfig
import com.example.data.model.Account
import com.example.data.model.AspectRatioMode
import com.example.data.model.CardStyle
import com.example.data.model.LiveChannelOrder
import com.example.data.model.UserSettings
import com.example.player.PlayerImmersiveHelper
import com.example.ui.components.KrasLogo
import com.example.ui.components.liquidGlassPanel
import com.example.ui.theme.*
import com.example.updater.AppUpdateUiState

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
    onCheckForUpdatesClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var showPinDialog by remember { mutableStateOf(false) }
    var enteredPin by remember { mutableStateOf("") }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showDndPermissionDialog by remember { mutableStateOf(false) }
    var cacheClearedFeedback by remember { mutableStateOf(false) }

    // Re-check DND permission when returning from system settings screen
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
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
                                    text = "Status: ${activeAccount.status} • Exp: ${activeAccount.expDate.ifEmpty { "Never" }}",
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
