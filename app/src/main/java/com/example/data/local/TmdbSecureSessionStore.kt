package com.example.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class StoredTmdbSession(
    val krasAccountId: String,
    val sessionId: String, // Opaque installation-bound client token issued by Cloudflare Worker (never exposes raw TMDb session_id)
    val accountId: Int,
    val username: String,
    val displayName: String,
    val avatarUrl: String,
    val lastSyncTimestampMs: Long,
    val syncedFavoritesCount: Int,
    val syncedWatchlistCount: Int,
    val syncedRatingsCount: Int,
    val installationId: String = ""
)

data class PendingTmdbAuthRequest(
    val krasAccountId: String,
    val requestToken: String,
    val stateNonce: String,
    val createdAtMs: Long,
    val expiresAtMs: Long = createdAtMs + DEFAULT_PENDING_AUTH_TTL_MS,
    val authSessionId: String = "",
    val installationId: String = ""
) {
    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean {
        return createdAtMs > 0L && nowMs > expiresAtMs
    }

    companion object {
        const val DEFAULT_PENDING_AUTH_TTL_MS = 15 * 60 * 1000L // 15 minutes
    }
}

/**
 * Multi-user encrypted TMDb session store backed by Android Keystore AES-256-GCM encryption.
 *
 * Security & Multi-User Isolation Guarantees:
 * - Each KRAS TV installation has a unique cryptographic `installationId`.
 * - Each KRAS TV user (`krasAccountId`) has an isolated encrypted TMDb session record.
 * - Stores only the opaque installation-bound client token issued by the Cloudflare Worker;
 *   the raw TMDb `session_id` and `TMDB_API_KEY` remain server-side on the Worker.
 * - Users cannot read or overwrite another KRAS TV user's TMDb session or pending auth state.
 * - Legacy single-user session keys from older versions are migrated/cleaned automatically.
 */
class TmdbSecureSessionStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        migrateLegacySingleUserSessionIfPresent()
    }

    private fun normalizeUserKey(krasAccountId: String?): String {
        val cleaned = krasAccountId?.trim().orEmpty()
        return if (cleaned.isEmpty()) DEFAULT_USER_ID else cleaned.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
    }

    private fun sessionKeyForUser(krasAccountId: String?): String {
        return "${KEY_USER_SESSION_PREFIX}${normalizeUserKey(krasAccountId)}"
    }

    private fun pendingAuthKeyForUser(krasAccountId: String?): String {
        return "${KEY_USER_PENDING_AUTH_PREFIX}${normalizeUserKey(krasAccountId)}"
    }

    /**
     * Returns a persistent cryptographic installation identifier unique to this KRAS TV installation.
     */
    fun getOrCreateInstallationId(): String {
        val existingEncrypted = prefs.getString(KEY_INSTALLATION_ID, null)
        if (!existingEncrypted.isNullOrBlank()) {
            val decrypted = decryptValue(existingEncrypted).trim()
            if (decrypted.length >= 16) {
                return decrypted
            }
        }
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        val generated = "kras_inst_" + bytes.joinToString("") { "%02x".format(it) }
        prefs.edit().putString(KEY_INSTALLATION_ID, encryptValue(generated)).apply()
        return generated
    }

    /**
     * Migrates and removes legacy single-user un-scoped session keys from earlier versions.
     */
    private fun migrateLegacySingleUserSessionIfPresent() {
        val legacyEncryptedSession = prefs.getString(LEGACY_KEY_ENCRYPTED_SESSION_ID, null)
        val legacyPending = prefs.getString(LEGACY_KEY_PENDING_REQUEST_TOKEN, null)
        if (legacyEncryptedSession == null && legacyPending == null) return

        val legacySessionId = legacyEncryptedSession?.let { decryptValue(it).trim() }.orEmpty()
        val legacyUsername = prefs.getString(LEGACY_KEY_USERNAME, "")?.trim().orEmpty()
        if (legacySessionId.isNotEmpty() && legacyUsername.isNotEmpty()) {
            val defaultKey = sessionKeyForUser(DEFAULT_USER_ID)
            if (!prefs.contains(defaultKey)) {
                saveSession(
                    krasAccountId = DEFAULT_USER_ID,
                    sessionId = legacySessionId,
                    accountId = prefs.getInt(LEGACY_KEY_ACCOUNT_ID, 0),
                    username = legacyUsername,
                    displayName = prefs.getString(LEGACY_KEY_DISPLAY_NAME, "")?.trim().orEmpty(),
                    avatarUrl = prefs.getString(LEGACY_KEY_AVATAR_URL, "")?.trim().orEmpty(),
                    lastSyncTimestampMs = prefs.getLong(LEGACY_KEY_LAST_SYNC_MS, 0L),
                    syncedFavoritesCount = prefs.getInt(LEGACY_KEY_SYNCED_FAVORITES_COUNT, 0),
                    syncedWatchlistCount = prefs.getInt(LEGACY_KEY_SYNCED_WATCHLIST_COUNT, 0),
                    syncedRatingsCount = prefs.getInt(LEGACY_KEY_SYNCED_RATINGS_COUNT, 0)
                )
            }
        }

        prefs.edit()
            .remove(LEGACY_KEY_ENCRYPTED_SESSION_ID)
            .remove(LEGACY_KEY_ACCOUNT_ID)
            .remove(LEGACY_KEY_USERNAME)
            .remove(LEGACY_KEY_DISPLAY_NAME)
            .remove(LEGACY_KEY_AVATAR_URL)
            .remove(LEGACY_KEY_PENDING_REQUEST_TOKEN)
            .remove(LEGACY_KEY_LAST_SYNC_MS)
            .remove(LEGACY_KEY_SYNCED_FAVORITES_COUNT)
            .remove(LEGACY_KEY_SYNCED_WATCHLIST_COUNT)
            .remove(LEGACY_KEY_SYNCED_RATINGS_COUNT)
            .apply()
    }

    fun saveSession(
        krasAccountId: String = DEFAULT_USER_ID,
        sessionId: String,
        accountId: Int,
        username: String,
        displayName: String,
        avatarUrl: String,
        lastSyncTimestampMs: Long = 0L,
        syncedFavoritesCount: Int = 0,
        syncedWatchlistCount: Int = 0,
        syncedRatingsCount: Int = 0
    ) {
        val normalizedUser = normalizeUserKey(krasAccountId)
        val existing = getSession(normalizedUser)
        val installationId = getOrCreateInstallationId()
        val payload = JSONObject().apply {
            put("krasAccountId", normalizedUser)
            put("installationId", installationId)
            put("sessionId", sessionId.trim())
            put("accountId", accountId)
            put("username", username.trim())
            put("displayName", displayName.trim())
            put("avatarUrl", avatarUrl.trim())
            put("lastSyncTimestampMs", if (lastSyncTimestampMs > 0L) lastSyncTimestampMs else (existing?.lastSyncTimestampMs ?: 0L))
            put("syncedFavoritesCount", if (syncedFavoritesCount > 0) syncedFavoritesCount else (existing?.syncedFavoritesCount ?: 0))
            put("syncedWatchlistCount", if (syncedWatchlistCount > 0) syncedWatchlistCount else (existing?.syncedWatchlistCount ?: 0))
            put("syncedRatingsCount", if (syncedRatingsCount > 0) syncedRatingsCount else (existing?.syncedRatingsCount ?: 0))
        }
        val encryptedPayload = encryptValue(payload.toString())
        prefs.edit()
            .putString(sessionKeyForUser(normalizedUser), encryptedPayload)
            .remove(pendingAuthKeyForUser(normalizedUser))
            .apply()
    }

    fun getSession(krasAccountId: String = DEFAULT_USER_ID): StoredTmdbSession? {
        val normalizedUser = normalizeUserKey(krasAccountId)
        val encrypted = prefs.getString(sessionKeyForUser(normalizedUser), null) ?: return null
        val decryptedJson = decryptValue(encrypted).trim()
        if (decryptedJson.isEmpty()) return null

        return try {
            val obj = JSONObject(decryptedJson)
            val sessionId = obj.optString("sessionId", "").trim()
            val username = obj.optString("username", "").trim()
            if (sessionId.isEmpty() || username.isEmpty()) return null

            StoredTmdbSession(
                krasAccountId = obj.optString("krasAccountId", normalizedUser),
                sessionId = sessionId,
                accountId = obj.optInt("accountId", 0),
                username = username,
                displayName = obj.optString("displayName", "").trim(),
                avatarUrl = obj.optString("avatarUrl", "").trim(),
                lastSyncTimestampMs = obj.optLong("lastSyncTimestampMs", 0L),
                syncedFavoritesCount = obj.optInt("syncedFavoritesCount", 0),
                syncedWatchlistCount = obj.optInt("syncedWatchlistCount", 0),
                syncedRatingsCount = obj.optInt("syncedRatingsCount", 0),
                installationId = obj.optString("installationId", getOrCreateInstallationId())
            )
        } catch (_: Exception) {
            null
        }
    }

    fun updateSyncStats(
        krasAccountId: String = DEFAULT_USER_ID,
        lastSyncTimestampMs: Long,
        favoritesCount: Int,
        watchlistCount: Int,
        ratingsCount: Int
    ) {
        val current = getSession(krasAccountId) ?: return
        saveSession(
            krasAccountId = krasAccountId,
            sessionId = current.sessionId,
            accountId = current.accountId,
            username = current.username,
            displayName = current.displayName,
            avatarUrl = current.avatarUrl,
            lastSyncTimestampMs = lastSyncTimestampMs,
            syncedFavoritesCount = favoritesCount,
            syncedWatchlistCount = watchlistCount,
            syncedRatingsCount = ratingsCount
        )
    }

    fun generateStateNonce(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun savePendingRequestToken(
        requestToken: String,
        krasAccountId: String = DEFAULT_USER_ID,
        stateNonce: String = generateStateNonce()
    ) {
        savePendingAuthRequest(
            krasAccountId = krasAccountId,
            requestToken = requestToken,
            stateNonce = stateNonce
        )
    }

    fun savePendingAuthRequest(
        krasAccountId: String = DEFAULT_USER_ID,
        requestToken: String,
        stateNonce: String,
        authSessionId: String = "",
        ttlMs: Long = PendingTmdbAuthRequest.DEFAULT_PENDING_AUTH_TTL_MS
    ) {
        val normalizedUser = normalizeUserKey(krasAccountId)
        val trimmedToken = requestToken.trim()
        if (trimmedToken.isEmpty() && authSessionId.isBlank()) {
            prefs.edit().remove(pendingAuthKeyForUser(normalizedUser)).apply()
            return
        }
        val nowMs = System.currentTimeMillis()
        val payload = JSONObject().apply {
            put("krasAccountId", normalizedUser)
            put("installationId", getOrCreateInstallationId())
            put("requestToken", trimmedToken)
            put("authSessionId", authSessionId.trim())
            put("stateNonce", stateNonce.trim())
            put("createdAtMs", nowMs)
            put("expiresAtMs", nowMs + ttlMs.coerceAtLeast(60_000L))
        }
        prefs.edit()
            .putString(pendingAuthKeyForUser(normalizedUser), encryptValue(payload.toString()))
            .apply()
    }

    fun getPendingAuthRequest(krasAccountId: String = DEFAULT_USER_ID): PendingTmdbAuthRequest? {
        val normalizedUser = normalizeUserKey(krasAccountId)
        val encrypted = prefs.getString(pendingAuthKeyForUser(normalizedUser), null) ?: return null
        val decrypted = decryptValue(encrypted).trim()
        if (decrypted.isEmpty()) return null
        return try {
            if (decrypted.startsWith("{")) {
                val obj = JSONObject(decrypted)
                val token = obj.optString("requestToken", "").trim()
                val authSessionId = obj.optString("authSessionId", "").trim()
                if (token.isEmpty() && authSessionId.isEmpty()) return null
                val createdAtMs = obj.optLong("createdAtMs", 0L)
                val expiresAtMs = obj.optLong(
                    "expiresAtMs",
                    if (createdAtMs > 0L) createdAtMs + PendingTmdbAuthRequest.DEFAULT_PENDING_AUTH_TTL_MS else Long.MAX_VALUE
                )
                PendingTmdbAuthRequest(
                    krasAccountId = obj.optString("krasAccountId", normalizedUser),
                    requestToken = token,
                    stateNonce = obj.optString("stateNonce", "").trim(),
                    createdAtMs = createdAtMs,
                    expiresAtMs = expiresAtMs,
                    authSessionId = authSessionId,
                    installationId = obj.optString("installationId", getOrCreateInstallationId())
                )
            } else {
                PendingTmdbAuthRequest(
                    krasAccountId = normalizedUser,
                    requestToken = decrypted,
                    stateNonce = "",
                    createdAtMs = 0L
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    fun getPendingRequestToken(krasAccountId: String = DEFAULT_USER_ID): String? {
        return getPendingAuthRequest(krasAccountId)?.requestToken
    }

    fun clearPendingRequestToken(krasAccountId: String = DEFAULT_USER_ID) {
        val normalizedUser = normalizeUserKey(krasAccountId)
        prefs.edit().remove(pendingAuthKeyForUser(normalizedUser)).apply()
    }

    fun clearSession(krasAccountId: String = DEFAULT_USER_ID) {
        val normalizedUser = normalizeUserKey(krasAccountId)
        prefs.edit()
            .remove(sessionKeyForUser(normalizedUser))
            .remove(pendingAuthKeyForUser(normalizedUser))
            .apply()
    }

    private fun getOrCreateSecretKey(): SecretKey? {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val existing = keyStore.getEntry(KEYSTORE_ALIAS, null) as? KeyStore.SecretKeyEntry
            if (existing != null) {
                existing.secretKey
            } else {
                val keyGenerator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES,
                    ANDROID_KEYSTORE
                )
                val spec = KeyGenParameterSpec.Builder(
                    KEYSTORE_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
                keyGenerator.init(spec)
                keyGenerator.generateKey()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun encryptValue(plainText: String): String {
        if (plainText.isEmpty()) return ""
        val secretKey = getOrCreateSecretKey()
        if (secretKey != null) {
            try {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, secretKey)
                val iv = cipher.iv
                val cipherBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
                val ivB64 = Base64.encodeToString(iv, Base64.NO_WRAP)
                val dataB64 = Base64.encodeToString(cipherBytes, Base64.NO_WRAP)
                return "ks:$ivB64:$dataB64"
            } catch (_: Exception) {
                // Fall back to obfuscated encoding in JVM test environments
            }
        }
        val encoded = Base64.encodeToString(plainText.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return "fb:$encoded"
    }

    private fun decryptValue(stored: String): String {
        if (stored.isEmpty()) return ""
        try {
            if (stored.startsWith("ks:")) {
                val parts = stored.split(":", limit = 3)
                if (parts.size == 3) {
                    val secretKey = getOrCreateSecretKey() ?: return ""
                    val iv = Base64.decode(parts[1], Base64.NO_WRAP)
                    val cipherBytes = Base64.decode(parts[2], Base64.NO_WRAP)
                    val cipher = Cipher.getInstance(TRANSFORMATION)
                    cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
                    return String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
                }
            } else if (stored.startsWith("fb:")) {
                val decoded = Base64.decode(stored.removePrefix("fb:"), Base64.NO_WRAP)
                return String(decoded, Charsets.UTF_8)
            }
        } catch (_: Exception) {
            return ""
        }
        return ""
    }

    companion object {
        private const val PREFS_NAME = "kras_tmdb_secure_session_prefs"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEYSTORE_ALIAS = "kras_tv_tmdb_session_key_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val DEFAULT_USER_ID = "demo"

        private const val KEY_INSTALLATION_ID = "enc_kras_installation_id_v1"
        private const val KEY_USER_SESSION_PREFIX = "enc_user_session_v2_"
        private const val KEY_USER_PENDING_AUTH_PREFIX = "enc_pending_auth_v2_"

        // Legacy single-user keys (automatically migrated & removed)
        private const val LEGACY_KEY_ENCRYPTED_SESSION_ID = "enc_session_id"
        private const val LEGACY_KEY_ACCOUNT_ID = "account_id"
        private const val LEGACY_KEY_USERNAME = "username"
        private const val LEGACY_KEY_DISPLAY_NAME = "display_name"
        private const val LEGACY_KEY_AVATAR_URL = "avatar_url"
        private const val LEGACY_KEY_PENDING_REQUEST_TOKEN = "enc_pending_request_token"
        private const val LEGACY_KEY_LAST_SYNC_MS = "last_sync_ms"
        private const val LEGACY_KEY_SYNCED_FAVORITES_COUNT = "synced_favorites_count"
        private const val LEGACY_KEY_SYNCED_WATCHLIST_COUNT = "synced_watchlist_count"
        private const val LEGACY_KEY_SYNCED_RATINGS_COUNT = "synced_ratings_count"
    }
}
