package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.UserSettings
import com.example.sync.ContentSyncStatus
import com.example.sync.SmartSyncUiState
import com.example.sync.SyncContentType
import com.example.sync.SyncOutcome
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SynchronizationSettingsCard(
    userSettings: UserSettings,
    syncState: SmartSyncUiState,
    isArabic: Boolean,
    onUpdateSettings: (UserSettings) -> Unit,
    onSyncNowClick: () -> Unit,
    onFullRefreshClick: () -> Unit,
    onSyncSingleTypeClick: (SyncContentType) -> Unit,
    modifier: Modifier = Modifier
) {
    var showCustomIntervals by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .liquidGlassPanel(cornerRadius = 20.dp)
            .testTag("settings_synchronization_card")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Sync,
                        contentDescription = if (isArabic) "المزامنة" else "Synchronization",
                        tint = KrasRedBright,
                        modifier = Modifier.size(20.dp)
                    )
                    Column {
                        Text(
                            text = if (isArabic) "المزامنة الذكية" else "Synchronization",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (isArabic) {
                                "محرك المزامنة الذكي — تحديث تلقائي صامت بدون تكرار"
                            } else {
                                "Global Smart Sync Engine • Silent incremental updates"
                            },
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            // Next scheduled synchronization summary pill
            val nextSyncText = remember(syncState.nextGlobalSyncMs, userSettings.smartAutoSyncEnabled, isArabic) {
                if (!userSettings.smartAutoSyncEnabled) {
                    if (isArabic) "المزامنة التلقائية متوقفة" else "Automatic sync paused"
                } else if (syncState.nextGlobalSyncMs > 0L) {
                    val formatted = formatSyncTimestamp(syncState.nextGlobalSyncMs, isArabic)
                    if (isArabic) "المزامنة المجدولة القادمة: $formatted" else "Next scheduled sync: $formatted"
                } else {
                    if (isArabic) "تتم الجدولة تلقائياً حسب الفترات المحددة" else "Scheduled automatically by content interval"
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.06f))
                    .border(1.dp, GlassBorder, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 9.dp)
                    .testTag("sync_next_scheduled_banner"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Schedule,
                    contentDescription = null,
                    tint = if (userSettings.smartAutoSyncEnabled) AccentCyan else TextSecondary,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = nextSyncText,
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
            }

            // Manual Sync Progress Banner (ONLY shown during user-initiated manual sync)
            if (syncState.isManualSyncRunning && !syncState.activeManualMessage.isNullOrBlank()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(KrasRed.copy(alpha = 0.16f))
                        .border(1.dp, KrasRed.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .testTag("sync_manual_progress_banner"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator(
                        color = KrasRedBright,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = syncState.activeManualMessage,
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Actionable Manual Error Banner (preserved when manual sync fails)
            if (!syncState.isManualSyncRunning && !syncState.lastManualError.isNullOrBlank()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0x33FF5252))
                        .border(1.dp, Color(0x80FF5252), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .testTag("sync_manual_error_banner"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ErrorOutline,
                        contentDescription = null,
                        tint = Color(0xFFFF8A80),
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = syncState.lastManualError,
                        color = Color(0xFFFFCDD2),
                        fontSize = 11.sp,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Primary Action Buttons: Sync Now & Full Refresh
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onSyncNowClick,
                    enabled = !syncState.isManualSyncRunning,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = KrasRed,
                        disabledContainerColor = KrasRed.copy(alpha = 0.4f)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("sync_now_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Sync,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isArabic) "مزامنة الآن" else "Sync Now",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                OutlinedButton(
                    onClick = onFullRefreshClick,
                    enabled = !syncState.isManualSyncRunning,
                    border = androidx.compose.foundation.BorderStroke(1.dp, KrasRedBright.copy(alpha = 0.7f)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("sync_full_refresh_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        tint = KrasRedBright,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isArabic) "تحديث كامل" else "Full Refresh",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

            // Toggle 1: Smart Auto Sync
            SyncToggleRow(
                title = if (isArabic) "المزامنة التلقائية الذكية" else "Smart Auto Sync",
                subtitle = if (isArabic) {
                    "مزامنة المحتوى القديم فقط في الخلفية بصمت حسب الفترة الزمنية"
                } else {
                    "Silently update stale items in the background based on smart intervals"
                },
                checked = userSettings.smartAutoSyncEnabled,
                onCheckedChange = { enabled ->
                    onUpdateSettings(userSettings.copy(smartAutoSyncEnabled = enabled))
                },
                tag = "sync_smart_auto_switch"
            )

            // Toggle 2: Wi-Fi Only
            SyncToggleRow(
                title = if (isArabic) "عبر Wi-Fi فقط" else "Wi-Fi Only",
                subtitle = if (isArabic) {
                    "تأجيل المزامنة التلقائية في الخلفية عند استخدام بيانات الهاتف"
                } else {
                    "Defer automatic background sync when on mobile data"
                },
                checked = userSettings.syncWifiOnly,
                onCheckedChange = { wifiOnly ->
                    onUpdateSettings(userSettings.copy(syncWifiOnly = wifiOnly))
                },
                icon = Icons.Default.Wifi,
                tag = "sync_wifi_only_switch"
            )

            // Toggle 3: Battery Saving Sync Mode
            SyncToggleRow(
                title = if (isArabic) "وضع توفير البطارية للمزامنة" else "Battery Saving Sync",
                subtitle = if (isArabic) {
                    "مضاعفة فترات المزامنة وإيقافها مؤقتاً عند انخفاض البطارية"
                } else {
                    "Doubles refresh intervals and pauses background sync on low battery"
                },
                checked = userSettings.batterySavingSyncMode,
                onCheckedChange = { batterySave ->
                    onUpdateSettings(userSettings.copy(batterySavingSyncMode = batterySave))
                },
                icon = Icons.Default.BatterySaver,
                tag = "sync_battery_saving_switch"
            )

            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

            // Individual Content Synchronization Status & Controls
            Text(
                text = if (isArabic) "حالة المزامنة حسب نوع المحتوى" else "Content Synchronization Controls",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SyncContentType.entries.forEach { type ->
                    val status = syncState.statuses[type] ?: ContentSyncStatus(type = type)
                    val intervalLabel = if (type.isChangeDrivenOnly) {
                        if (isArabic) "عند التغيير فقط" else "Only when changed"
                    } else {
                        val effectiveHours = type.configuredIntervalHours(userSettings) *
                            if (userSettings.batterySavingSyncMode) 2 else 1
                        if (isArabic) "كل $effectiveHours ساعة" else "Every ${effectiveHours}h"
                    }

                    ContentSyncRow(
                        type = type,
                        status = status,
                        intervalLabel = intervalLabel,
                        isArabic = isArabic,
                        isManualSyncBusy = syncState.isManualSyncRunning && !status.isRunning,
                        onSyncClick = { onSyncSingleTypeClick(type) }
                    )
                }
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

            // Expandable Custom Synchronization Intervals
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White.copy(alpha = 0.05f))
                    .clickable { showCustomIntervals = !showCustomIntervals }
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .testTag("sync_custom_intervals_toggle"),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isArabic) "فترات المزامنة المخصصة" else "Custom Sync Intervals",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (isArabic) {
                            "تعديل الفترة الزمنية لكل قسم بشكل مستقل"
                        } else {
                            "Customize refresh intervals independently for each content type"
                        },
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                }
                Icon(
                    imageVector = if (showCustomIntervals) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (showCustomIntervals) "Collapse" else "Expand",
                    tint = Color.White
                )
            }

            AnimatedVisibility(visible = showCustomIntervals) {
                Column(
                    modifier = Modifier.padding(top = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    IntervalSelectorRow(
                        label = SyncContentType.MOVIES.label(isArabic),
                        selectedHours = userSettings.syncIntervalMoviesHours,
                        options = listOf(3, 6, 12, 24, 48),
                        isArabic = isArabic,
                        tagPrefix = "interval_movies",
                        onSelect = { h -> onUpdateSettings(userSettings.copy(syncIntervalMoviesHours = h)) }
                    )
                    IntervalSelectorRow(
                        label = SyncContentType.SERIES.label(isArabic),
                        selectedHours = userSettings.syncIntervalSeriesHours,
                        options = listOf(3, 6, 12, 24, 48),
                        isArabic = isArabic,
                        tagPrefix = "interval_series",
                        onSelect = { h -> onUpdateSettings(userSettings.copy(syncIntervalSeriesHours = h)) }
                    )
                    IntervalSelectorRow(
                        label = SyncContentType.LIVE_TV.label(isArabic),
                        selectedHours = userSettings.syncIntervalLiveHours,
                        options = listOf(6, 12, 24, 48),
                        isArabic = isArabic,
                        tagPrefix = "interval_live",
                        onSelect = { h -> onUpdateSettings(userSettings.copy(syncIntervalLiveHours = h)) }
                    )
                    IntervalSelectorRow(
                        label = SyncContentType.CATEGORIES.label(isArabic),
                        selectedHours = userSettings.syncIntervalCategoriesHours,
                        options = listOf(12, 24, 48, 72),
                        isArabic = isArabic,
                        tagPrefix = "interval_categories",
                        onSelect = { h -> onUpdateSettings(userSettings.copy(syncIntervalCategoriesHours = h)) }
                    )
                    IntervalSelectorRow(
                        label = SyncContentType.EPG.label(isArabic),
                        selectedHours = userSettings.syncIntervalEpgHours,
                        options = listOf(1, 3, 6, 12, 24),
                        isArabic = isArabic,
                        tagPrefix = "interval_epg",
                        onSelect = { h -> onUpdateSettings(userSettings.copy(syncIntervalEpgHours = h)) }
                    )
                    IntervalSelectorRow(
                        label = SyncContentType.TMDB_METADATA.label(isArabic),
                        selectedHours = userSettings.syncIntervalTmdbHours,
                        options = listOf(12, 24, 48, 72),
                        isArabic = isArabic,
                        tagPrefix = "interval_tmdb",
                        onSelect = { h -> onUpdateSettings(userSettings.copy(syncIntervalTmdbHours = h)) }
                    )
                    IntervalSelectorRow(
                        label = SyncContentType.ACCOUNT_STATUS.label(isArabic),
                        selectedHours = userSettings.syncIntervalAccountHours,
                        options = listOf(12, 24, 48, 72),
                        isArabic = isArabic,
                        tagPrefix = "interval_account",
                        onSelect = { h -> onUpdateSettings(userSettings.copy(syncIntervalAccountHours = h)) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SyncToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    tag: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (checked) KrasRedBright else TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
            Column {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = subtitle,
                    color = TextSecondary,
                    fontSize = 11.sp
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = KrasRed,
                checkedTrackColor = KrasRedDark
            ),
            modifier = Modifier.testTag(tag)
        )
    }
}

@Composable
private fun ContentSyncRow(
    type: SyncContentType,
    status: ContentSyncStatus,
    intervalLabel: String,
    isArabic: Boolean,
    isManualSyncBusy: Boolean,
    onSyncClick: () -> Unit
) {
    val lastSyncedLabel = remember(status.lastSuccessMs, isArabic) {
        if (status.lastSuccessMs > 0L) {
            val formatted = formatSyncTimestamp(status.lastSuccessMs, isArabic)
            if (isArabic) "آخر مزامنة: $formatted" else "Last synced: $formatted"
        } else {
            if (isArabic) "لم تتم المزامنة بعد" else "Last synced: Never"
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, GlassBorder, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("sync_row_${type.name.lowercase()}"),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = type.label(isArabic),
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "• $intervalLabel",
                    color = AccentCyan.copy(alpha = 0.85f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = lastSyncedLabel,
                color = TextSecondary,
                fontSize = 10.sp
            )
            if (status.lastOutcome == SyncOutcome.FAILED && !status.lastError.isNullOrBlank()) {
                Text(
                    text = status.lastError,
                    color = Color(0xFFFF8A80),
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        IconButton(
            onClick = onSyncClick,
            enabled = !status.isRunning && !isManualSyncBusy,
            modifier = Modifier
                .size(48.dp)
                .testTag("sync_single_${type.name.lowercase()}")
        ) {
            if (status.isRunning) {
                CircularProgressIndicator(
                    color = KrasRedBright,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.09f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (status.lastOutcome == SyncOutcome.SUCCESS || status.lastOutcome == SyncOutcome.NOT_MODIFIED) {
                            Icons.Default.Sync
                        } else {
                            Icons.Default.Refresh
                        },
                        contentDescription = if (isArabic) "مزامنة ${type.labelAr}" else "Sync ${type.labelEn}",
                        tint = KrasRedBright,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun IntervalSelectorRow(
    label: String,
    selectedHours: Int,
    options: List<Int>,
    isArabic: Boolean,
    tagPrefix: String,
    onSelect: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            color = TextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            options.forEach { hours ->
                val isSelected = selectedHours == hours
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) KrasRed else Color.White.copy(alpha = 0.08f))
                        .border(
                            width = 1.dp,
                            color = if (isSelected) KrasRedBright else GlassBorder,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .clickable { onSelect(hours) }
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                        .testTag("${tagPrefix}_${hours}h"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isArabic) "${hours}س" else "${hours}h",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }
    }
}

private fun formatSyncTimestamp(timestampMs: Long, isArabic: Boolean): String {
    return try {
        val locale = if (isArabic) Locale("ar") else Locale.getDefault()
        val pattern = "MMM d, HH:mm"
        SimpleDateFormat(pattern, locale).format(Date(timestampMs))
    } catch (_: Exception) {
        "—"
    }
}
