package com.example.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.example.BuildConfig
import com.example.data.local.KrasRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

data class GithubReleaseInfo(
    val tagName: String,
    val versionNumber: Int,
    val body: String,
    val assetName: String,
    val downloadUrl: String,
    val assetSize: Long
)

sealed class ReleaseCheckResult {
    data class UpdateAvailable(val release: GithubReleaseInfo) : ReleaseCheckResult()
    data class UpToDate(
        val currentVersionCode: Int,
        val latestVersionNumber: Int,
        val tagName: String
    ) : ReleaseCheckResult()
    data class RateLimited(val message: String) : ReleaseCheckResult()
    data class MissingAsset(val message: String) : ReleaseCheckResult()
    data class NetworkError(val message: String) : ReleaseCheckResult()
    data class Error(val message: String) : ReleaseCheckResult()
}

data class AppUpdateUiState(
    val isChecking: Boolean = false,
    val availableRelease: GithubReleaseInfo? = null,
    val showUpdateDialog: Boolean = false,
    val isDownloading: Boolean = false,
    val downloadProgress: Int = 0,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val errorMessage: String? = null,
    val statusMessage: String? = null,
    val isStatusError: Boolean = false,
    val pendingInstallFile: File? = null,
    val showInstallPermissionDialog: Boolean = false
)

class AppUpdater(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
) {
    companion object {
        const val APK_ASSET_NAME = "KrasTV.apk"
        const val SIX_HOURS_MS = 6L * 60L * 60L * 1000L
        private const val UPDATES_DIR_NAME = "updates"

        val latestReleaseApiUrl: String
            get() = "https://api.github.com/repos/${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO}/releases/latest"

        /**
         * Extracts the numeric version from a GitHub tag_name (e.g. "v12" -> 12).
         */
        fun parseTagVersionNumber(tagName: String?): Int {
            if (tagName.isNullOrBlank()) return 0
            return try {
                val trimmed = tagName.trim().removePrefix("v").removePrefix("V")
                trimmed.toIntOrNull()
                    ?: Regex("""\d+""").find(trimmed)?.value?.toIntOrNull()
                    ?: 0
            } catch (e: Exception) {
                0
            }
        }

        /**
         * Parses the JSON response from GitHub /releases/latest and compares tag_name against currentVersionCode.
         */
        fun parseReleaseJson(
            jsonStr: String,
            currentVersionCode: Int = BuildConfig.VERSION_CODE
        ): ReleaseCheckResult {
            return try {
                val root = JSONObject(jsonStr)
                val tagName = root.optString("tag_name", "").trim()
                if (tagName.isEmpty()) {
                    return ReleaseCheckResult.Error("Invalid release information received from GitHub.")
                }

                val versionNumber = parseTagVersionNumber(tagName)
                if (versionNumber <= 0) {
                    return ReleaseCheckResult.Error("Invalid release tag format: $tagName")
                }

                val body = root.optString("body", "").trim().ifEmpty {
                    "Bug fixes and performance improvements."
                }

                val assetsArray = root.optJSONArray("assets")
                var foundDownloadUrl = ""
                var foundSize = 0L
                var foundAssetName = ""

                if (assetsArray != null) {
                    for (i in 0 until assetsArray.length()) {
                        val assetObj = assetsArray.optJSONObject(i) ?: continue
                        val name = assetObj.optString("name", "").trim()
                        if (name.equals(APK_ASSET_NAME, ignoreCase = true)) {
                            foundAssetName = name
                            foundDownloadUrl = assetObj.optString("browser_download_url", "").trim()
                            foundSize = assetObj.optLong("size", 0L)
                            break
                        }
                    }
                }

                if (versionNumber <= currentVersionCode) {
                    return ReleaseCheckResult.UpToDate(
                        currentVersionCode = currentVersionCode,
                        latestVersionNumber = versionNumber,
                        tagName = tagName
                    )
                }

                if (foundDownloadUrl.isBlank()) {
                    return ReleaseCheckResult.MissingAsset("Release $tagName does not contain $APK_ASSET_NAME.")
                }

                ReleaseCheckResult.UpdateAvailable(
                    GithubReleaseInfo(
                        tagName = tagName,
                        versionNumber = versionNumber,
                        body = body,
                        assetName = foundAssetName.ifEmpty { APK_ASSET_NAME },
                        downloadUrl = foundDownloadUrl,
                        assetSize = foundSize
                    )
                )
            } catch (e: Exception) {
                ReleaseCheckResult.Error("Failed to parse update details.")
            }
        }

        fun getUpdatesDir(context: Context): File {
            val dir = File(context.cacheDir, UPDATES_DIR_NAME)
            if (!dir.exists()) {
                dir.mkdirs()
            }
            return dir
        }

        /**
         * Deletes old downloaded APKs in cacheDir/updates after a successful app update.
         */
        fun cleanupOldApksAfterUpdate(
            context: Context,
            repository: KrasRepository,
            currentVersionCode: Int = BuildConfig.VERSION_CODE
        ) {
            try {
                val lastKnown = repository.getLastKnownVersionCode()
                val updatesDir = File(context.cacheDir, UPDATES_DIR_NAME)
                if (lastKnown != currentVersionCode) {
                    if (updatesDir.exists()) {
                        updatesDir.listFiles()?.forEach { file ->
                            try {
                                file.delete()
                            } catch (_: Exception) {
                            }
                        }
                    }
                    repository.saveLastKnownVersionCode(currentVersionCode)
                } else if (updatesDir.exists()) {
                    // Also clean up any leftover partial .tmp files
                    updatesDir.listFiles()?.filter { it.name.endsWith(".tmp") }?.forEach { file ->
                        try {
                            file.delete()
                        } catch (_: Exception) {
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun deleteAllDownloadedApks(context: Context) {
            try {
                val updatesDir = File(context.cacheDir, UPDATES_DIR_NAME)
                if (updatesDir.exists()) {
                    updatesDir.listFiles()?.forEach { file ->
                        try {
                            file.delete()
                        } catch (_: Exception) {
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun canRequestPackageInstalls(context: Context): Boolean {
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.packageManager.canRequestPackageInstalls()
                } else {
                    true
                }
            } catch (e: Exception) {
                false
            }
        }

        fun createManageUnknownAppSourcesIntent(context: Context): Intent {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            } else {
                Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        fun createInstallApkIntent(context: Context, apkFile: File): Intent {
            val authority = "${context.packageName}.fileprovider"
            val apkUri = FileProvider.getUriForFile(context, authority, apkFile)
            return Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    @Volatile
    private var activeDownloadCall: Call? = null

    /**
     * Checks GitHub Releases for a newer version on a background thread.
     */
    suspend fun checkLatestRelease(
        currentVersionCode: Int = BuildConfig.VERSION_CODE,
        apiUrl: String = latestReleaseApiUrl
    ): ReleaseCheckResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(apiUrl)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "KrasTV-Android/${BuildConfig.VERSION_NAME}")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                val code = response.code
                if (code == 403 || code == 429) {
                    return@withContext ReleaseCheckResult.RateLimited(
                        "GitHub API rate limit reached (HTTP $code). Please try again later."
                    )
                }
                if (code == 404) {
                    return@withContext ReleaseCheckResult.MissingAsset(
                        "No published releases found."
                    )
                }
                if (!response.isSuccessful) {
                    return@withContext ReleaseCheckResult.Error(
                        "Update server returned HTTP $code."
                    )
                }

                val bodyStr = response.body?.string().orEmpty()
                if (bodyStr.isBlank()) {
                    return@withContext ReleaseCheckResult.Error("Empty response from update server.")
                }

                parseReleaseJson(bodyStr, currentVersionCode)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (ioe: IOException) {
            ReleaseCheckResult.NetworkError("Unable to reach update server. Check your internet connection.")
        } catch (e: Exception) {
            ReleaseCheckResult.Error(e.message ?: "Unexpected error while checking for updates.")
        }
    }

    /**
     * Streams the APK directly to cacheDir/updates without loading into memory,
     * reports progress percentage, and verifies downloaded size matches the release asset size.
     */
    suspend fun downloadApk(
        context: Context,
        release: GithubReleaseInfo,
        onProgress: (progressPercent: Int, downloadedBytes: Long, totalBytes: Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val updatesDir = getUpdatesDir(context)
        val tempFile = File(updatesDir, "KrasTV_${release.tagName}.apk.tmp")
        val targetFile = File(updatesDir, APK_ASSET_NAME)

        try {
            if (tempFile.exists()) {
                tempFile.delete()
            }

            val request = Request.Builder()
                .url(release.downloadUrl)
                .header("Accept", "application/octet-stream")
                .header("User-Agent", "KrasTV-Android/${BuildConfig.VERSION_NAME}")
                .get()
                .build()

            val call = httpClient.newCall(request)
            activeDownloadCall = call

            call.execute().use { response ->
                if (response.code == 403 || response.code == 429) {
                    return@withContext Result.failure(
                        IOException("Download rate limit reached (HTTP ${response.code}). Please try again later.")
                    )
                }
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IOException("Download failed with HTTP ${response.code}.")
                    )
                }

                val body = response.body ?: return@withContext Result.failure(
                    IOException("Empty APK file response from server.")
                )

                val contentLength = body.contentLength()
                val expectedTotal = if (release.assetSize > 0L) {
                    release.assetSize
                } else if (contentLength > 0L) {
                    contentLength
                } else {
                    0L
                }

                onProgress(0, 0L, expectedTotal)

                body.byteStream().use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(8 * 1024)
                        var bytesRead: Int
                        var totalRead = 0L
                        var lastReportedProgress = -1

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            currentCoroutineContext().ensureActive()
                            output.write(buffer, 0, bytesRead)
                            totalRead += bytesRead

                            val pct = if (expectedTotal > 0L) {
                                ((totalRead * 100L) / expectedTotal).toInt().coerceIn(0, 100)
                            } else {
                                0
                            }
                            if (pct != lastReportedProgress) {
                                lastReportedProgress = pct
                                onProgress(pct, totalRead, expectedTotal)
                            }
                        }
                        output.flush()
                    }
                }
            }

            val finalSize = tempFile.length()
            if (finalSize <= 0L) {
                tempFile.delete()
                return@withContext Result.failure(IOException("Downloaded APK file is empty."))
            }

            // Verify downloaded size matches the release asset size
            if (release.assetSize > 0L && finalSize != release.assetSize) {
                tempFile.delete()
                return@withContext Result.failure(
                    IOException(
                        "Downloaded APK size ($finalSize bytes) does not match expected size (${release.assetSize} bytes)."
                    )
                )
            }

            // Clean up any existing final APK before replacing
            updatesDir.listFiles()?.forEach { existing ->
                if (existing.absolutePath != tempFile.absolutePath) {
                    try {
                        existing.delete()
                    } catch (_: Exception) {
                    }
                }
            }

            val moved = tempFile.renameTo(targetFile)
            val finalApk = if (moved) targetFile else tempFile
            onProgress(100, finalSize, finalSize)
            Result.success(finalApk)
        } catch (ce: CancellationException) {
            try {
                tempFile.delete()
            } catch (_: Exception) {
            }
            throw ce
        } catch (e: Exception) {
            try {
                tempFile.delete()
            } catch (_: Exception) {
            }
            Result.failure(e)
        } finally {
            activeDownloadCall = null
        }
    }

    fun cancelActiveDownload() {
        try {
            activeDownloadCall?.cancel()
        } catch (_: Exception) {
        } finally {
            activeDownloadCall = null
        }
    }
}
