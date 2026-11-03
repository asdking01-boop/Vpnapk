package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*
import com.example.updater.AppUpdateUiState

@Composable
fun AppUpdateDialogs(
    updateState: AppUpdateUiState,
    onStartUpdate: () -> Unit,
    onDismissDialog: () -> Unit,
    onCancelDownload: () -> Unit,
    onRetryDownload: () -> Unit,
    onOpenInstallPermissionSettings: () -> Unit,
    onDismissInstallPermissionDialog: () -> Unit
) {
    val release = updateState.availableRelease

    // 1. Unknown App Sources Permission Dialog
    if (updateState.showInstallPermissionDialog) {
        AlertDialog(
            onDismissRequest = onDismissInstallPermissionDialog,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = "Install Permission",
                        tint = KrasRedBright,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Allow App Installation",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "To install the downloaded Kras TV update, Android requires permission to install apps from this source.",
                        color = Color.White,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                    Text(
                        text = "Tap \"Open Settings\", enable \"Allow from this source\", and return to Kras TV — the installation will continue automatically.",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = onOpenInstallPermissionSettings,
                    colors = ButtonDefaults.buttonColors(containerColor = KrasRed),
                    modifier = Modifier.testTag("update_open_install_settings_button")
                ) {
                    Text("Open Settings", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = onDismissInstallPermissionDialog,
                    modifier = Modifier.testTag("update_cancel_install_settings_button")
                ) {
                    Text("Later", color = TextSecondary)
                }
            },
            containerColor = CinemaDark,
            shape = RoundedCornerShape(16.dp)
        )
        return
    }

    // 2. Main Update Available / Downloading / Error Dialog
    if (updateState.showUpdateDialog && release != null) {
        AlertDialog(
            onDismissRequest = {
                if (!updateState.isDownloading) {
                    onDismissDialog()
                }
            },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.SystemUpdate,
                        contentDescription = "Update Available",
                        tint = KrasRedBright,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "New version available",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                        Text(
                            text = "Version ${release.tagName}" +
                                    if (release.assetSize > 0L) " • ${formatBytes(release.assetSize)}" else "",
                            color = KrasRedBright,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.testTag("update_dialog_content")
                ) {
                    Text(
                        text = "Release Notes:",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 60.dp, max = 180.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF101014))
                            .border(1.dp, GlassBorder, RoundedCornerShape(10.dp))
                            .padding(10.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = release.body,
                            color = Color.White,
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        )
                    }

                    if (updateState.isDownloading) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Downloading ${release.assetName}...",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "${updateState.downloadProgress}%",
                                    color = KrasRedBright,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.testTag("update_download_percentage")
                                )
                            }
                            LinearProgressIndicator(
                                progress = { (updateState.downloadProgress / 100f).coerceIn(0f, 1f) },
                                color = KrasRed,
                                trackColor = Color.White.copy(alpha = 0.15f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .testTag("update_download_progress_bar")
                            )
                            if (updateState.totalBytes > 0L) {
                                Text(
                                    text = "${formatBytes(updateState.downloadedBytes)} / ${formatBytes(updateState.totalBytes)}",
                                    color = TextSecondary,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }

                    if (updateState.errorMessage != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF2A0C0E))
                                .border(1.dp, KrasRed.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                .padding(10.dp)
                                .testTag("update_error_box")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "Error",
                                tint = KrasRedBright,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = updateState.errorMessage,
                                color = Color.White,
                                fontSize = 12.sp,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }
            },
            confirmButton = {
                when {
                    updateState.isDownloading -> {
                        // While downloading, only show Cancel in dismissButton
                    }
                    updateState.errorMessage != null -> {
                        Button(
                            onClick = onRetryDownload,
                            colors = ButtonDefaults.buttonColors(containerColor = KrasRed),
                            modifier = Modifier.testTag("update_retry_button")
                        ) {
                            Text("Retry", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                    else -> {
                        Button(
                            onClick = onStartUpdate,
                            colors = ButtonDefaults.buttonColors(containerColor = KrasRed),
                            modifier = Modifier.testTag("update_now_button")
                        ) {
                            Text("Update now", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            },
            dismissButton = {
                if (updateState.isDownloading) {
                    TextButton(
                        onClick = onCancelDownload,
                        modifier = Modifier.testTag("update_cancel_download_button")
                    ) {
                        Text("Cancel", color = KrasRedBright, fontWeight = FontWeight.Bold)
                    }
                } else {
                    TextButton(
                        onClick = onDismissDialog,
                        modifier = Modifier.testTag("update_later_button")
                    ) {
                        Text("Later", color = TextSecondary)
                    }
                }
            },
            containerColor = CinemaDark,
            shape = RoundedCornerShape(16.dp)
        )
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val mb = bytes.toDouble() / (1024.0 * 1024.0)
    return if (mb >= 1.0) {
        String.format("%.1f MB", mb)
    } else {
        val kb = bytes.toDouble() / 1024.0
        String.format("%.0f KB", kb)
    }
}
