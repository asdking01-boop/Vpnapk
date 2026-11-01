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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Account
import com.example.data.model.AspectRatioMode
import com.example.data.model.CardStyle
import com.example.data.model.UserSettings
import com.example.ui.theme.*

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
    modifier: Modifier = Modifier
) {
    var showPinDialog by remember { mutableStateOf(false) }
    var enteredPin by remember { mutableStateOf("") }
    var showAboutDialog by remember { mutableStateOf(false) }
    var cacheClearedFeedback by remember { mutableStateOf(false) }

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
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 14.dp)
            )
        }

        // 1. Account Management Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = CinemaCard),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, GlassBorder, RoundedCornerShape(16.dp))
                    .padding(bottom = 16.dp)
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

        // 2. Theme & Customization
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = CinemaCard),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, GlassBorder, RoundedCornerShape(16.dp))
                    .padding(bottom = 16.dp)
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

        // 3. Playback & Streaming Engine
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = CinemaCard),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, GlassBorder, RoundedCornerShape(16.dp))
                    .padding(bottom = 16.dp)
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
                        listOf(AspectRatioMode.FIT to "Fit", AspectRatioMode.FILL to "Fill", AspectRatioMode.STRETCH to "Stretch").forEach { (mode, label) ->
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
                }
            }
        }

        // 4. Parental Controls & Security
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = CinemaCard),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, GlassBorder, RoundedCornerShape(16.dp))
                    .padding(bottom = 16.dp)
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
            Card(
                colors = CardDefaults.cardColors(containerColor = CinemaCard),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, GlassBorder, RoundedCornerShape(16.dp))
                    .padding(bottom = 24.dp)
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
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(KrasRed),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("K", color = Color.White, fontWeight = FontWeight.Black, fontSize = 20.sp)
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Kras TV v2.4", color = Color.White, fontWeight = FontWeight.Bold)
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
}
