package com.example.sync

import android.content.Context
import android.content.SharedPreferences
import com.example.data.api.ConditionalHttpHeaders
import com.example.data.model.Account
import java.security.MessageDigest

/**
 * Persistent, account-isolated synchronization metadata store.
 * Keeps timestamps, HTTP ETag / Last-Modified headers, credentials fingerprints,
 * exponential retry backoff schedules, and Favorites/Watch History dirty flags
 * strictly scoped per IPTV account without modifying the Room database schema.
 */
class SmartSyncStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun safeAccountKey(accountId: String): String {
        val raw = accountId.trim().ifEmpty { "default" }
        return raw.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
    }

    private fun prefix(accountId: String, type: SyncContentType): String =
        "acc_${safeAccountKey(accountId)}_${type.name.lowercase()}"

    private fun subHeaderPrefix(accountId: String, subKey: String): String =
        "acc_${safeAccountKey(accountId)}_hdr_${subKey.lowercase()}"

    private fun accountPrefix(accountId: String): String =
        "acc_${safeAccountKey(accountId)}_"

    // --- Timestamps & Outcomes ---

    fun getLastSuccessMs(accountId: String, type: SyncContentType): Long =
        prefs.getLong("${prefix(accountId, type)}_last_success_ms", 0L)

    fun getLastAttemptMs(accountId: String, type: SyncContentType): Long =
        prefs.getLong("${prefix(accountId, type)}_last_attempt_ms", 0L)

    fun getConsecutiveFailures(accountId: String, type: SyncContentType): Int =
        prefs.getInt("${prefix(accountId, type)}_failures", 0)

    fun getNextRetryAtMs(accountId: String, type: SyncContentType): Long =
        prefs.getLong("${prefix(accountId, type)}_next_retry_ms", 0L)

    fun getLastError(accountId: String, type: SyncContentType): String? =
        prefs.getString("${prefix(accountId, type)}_last_error", null)

    fun getLastOutcome(accountId: String, type: SyncContentType): SyncOutcome {
        val name = prefs.getString("${prefix(accountId, type)}_last_outcome", SyncOutcome.NEVER.name)
        return try {
            SyncOutcome.valueOf(name ?: SyncOutcome.NEVER.name)
        } catch (_: Exception) {
            SyncOutcome.NEVER
        }
    }

    fun getConditionalHeaders(accountId: String, subKey: String): ConditionalHttpHeaders {
        val p = subHeaderPrefix(accountId, subKey)
        return ConditionalHttpHeaders(
            etag = prefs.getString("${p}_etag", null),
            lastModified = prefs.getString("${p}_last_modified", null)
        )
    }

    fun saveConditionalHeaders(
        accountId: String,
        subKey: String,
        headers: ConditionalHttpHeaders
    ) {
        val p = subHeaderPrefix(accountId, subKey)
        val editor = prefs.edit()
        if (!headers.etag.isNullOrBlank()) {
            editor.putString("${p}_etag", headers.etag)
        }
        if (!headers.lastModified.isNullOrBlank()) {
            editor.putString("${p}_last_modified", headers.lastModified)
        }
        editor.apply()
    }

    fun clearConditionalHeaders(accountId: String, subKey: String? = null) {
        val editor = prefs.edit()
        if (subKey != null) {
            val p = subHeaderPrefix(accountId, subKey)
            editor.remove("${p}_etag").remove("${p}_last_modified")
        } else {
            val accHeaderPrefix = "acc_${safeAccountKey(accountId)}_hdr_"
            prefs.all.keys
                .filter { it.startsWith(accHeaderPrefix) }
                .forEach { editor.remove(it) }
        }
        editor.apply()
    }

    /**
     * Records a successful sync (including HTTP 304 Not Modified), clearing any backoff
     * and error state. Only called when the operation truly succeeded.
     */
    fun recordSuccess(
        accountId: String,
        type: SyncContentType,
        timestampMs: Long,
        outcome: SyncOutcome = SyncOutcome.SUCCESS,
        subHeaderKey: String? = null,
        headers: ConditionalHttpHeaders? = null
    ) {
        val p = prefix(accountId, type)
        val editor = prefs.edit()
            .putLong("${p}_last_success_ms", timestampMs)
            .putLong("${p}_last_attempt_ms", timestampMs)
            .putInt("${p}_failures", 0)
            .putLong("${p}_next_retry_ms", 0L)
            .remove("${p}_last_error")
            .putString("${p}_last_outcome", outcome.name)

        if (subHeaderKey != null && headers != null) {
            val hp = subHeaderPrefix(accountId, subHeaderKey)
            if (!headers.etag.isNullOrBlank()) editor.putString("${hp}_etag", headers.etag)
            if (!headers.lastModified.isNullOrBlank()) editor.putString("${hp}_last_modified", headers.lastModified)
        }
        editor.apply()
    }

    /**
     * Records a failed sync with exponential backoff (5m -> 10m -> 20m -> 40m -> ... capped at 6h).
     * NEVER updates `lastSuccessMs`, ensuring failed syncs are never treated as successful.
     */
    fun recordFailure(
        accountId: String,
        type: SyncContentType,
        timestampMs: Long,
        errorMessage: String
    ): Long {
        val p = prefix(accountId, type)
        val failures = getConsecutiveFailures(accountId, type) + 1
        val backoffMs = computeExponentialBackoffMs(failures)
        val nextRetryAtMs = timestampMs + backoffMs
        prefs.edit()
            .putLong("${p}_last_attempt_ms", timestampMs)
            .putInt("${p}_failures", failures)
            .putLong("${p}_next_retry_ms", nextRetryAtMs)
            .putString("${p}_last_error", errorMessage.take(300))
            .putString("${p}_last_outcome", SyncOutcome.FAILED.name)
            .apply()
        return nextRetryAtMs
    }

    fun resetBackoff(accountId: String, type: SyncContentType) {
        val p = prefix(accountId, type)
        prefs.edit()
            .putInt("${p}_failures", 0)
            .putLong("${p}_next_retry_ms", 0L)
            .remove("${p}_last_error")
            .apply()
    }

    // --- Account Credentials Fingerprint (Detects Server URL / Username / Password Changes) ---

    fun getStoredCredentialsFingerprint(accountId: String): String? =
        prefs.getString("${accountPrefix(accountId)}cred_fingerprint", null)

    fun saveCredentialsFingerprint(account: Account) {
        prefs.edit()
            .putString("${accountPrefix(account.id)}cred_fingerprint", computeCredentialsFingerprint(account))
            .apply()
    }

    fun hasCredentialsChanged(account: Account): Boolean {
        val stored = getStoredCredentialsFingerprint(account.id) ?: return false
        return stored != computeCredentialsFingerprint(account)
    }

    fun isNewAccount(account: Account): Boolean {
        return getStoredCredentialsFingerprint(account.id) == null
    }

    // --- Event-Driven Dirty Flags for Favorites & Watch History ---

    fun markFavoritesOrHistoryDirty(accountId: String, timestampMs: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putBoolean("${accountPrefix(accountId)}fav_history_dirty", true)
            .putLong("${accountPrefix(accountId)}fav_history_dirty_ms", timestampMs)
            .apply()
    }

    fun isFavoritesOrHistoryDirty(accountId: String): Boolean =
        prefs.getBoolean("${accountPrefix(accountId)}fav_history_dirty", false)

    fun clearFavoritesOrHistoryDirty(accountId: String) {
        prefs.edit()
            .putBoolean("${accountPrefix(accountId)}fav_history_dirty", false)
            .apply()
    }

    // --- Account Cleanup & Snapshot ---

    fun clearAccount(accountId: String) {
        val accPref = accountPrefix(accountId)
        val editor = prefs.edit()
        prefs.all.keys
            .filter { it.startsWith(accPref) }
            .forEach { editor.remove(it) }
        editor.apply()
    }

    fun clearCatalogTimestamps(accountId: String) {
        val editor = prefs.edit()
        for (type in SyncContentType.entries) {
            val p = prefix(accountId, type)
            editor
                .remove("${p}_last_success_ms")
                .remove("${p}_last_attempt_ms")
                .remove("${p}_failures")
                .remove("${p}_next_retry_ms")
                .remove("${p}_last_error")
                .remove("${p}_last_outcome")
        }
        val accHeaderPrefix = "acc_${safeAccountKey(accountId)}_hdr_"
        prefs.all.keys
            .filter { it.startsWith(accHeaderPrefix) }
            .forEach { editor.remove(it) }
        editor.apply()
    }

    fun buildStatus(
        accountId: String,
        type: SyncContentType,
        intervalMs: Long,
        isRunning: Boolean
    ): ContentSyncStatus {
        val lastSuccess = getLastSuccessMs(accountId, type)
        val lastAttempt = getLastAttemptMs(accountId, type)
        val failures = getConsecutiveFailures(accountId, type)
        val nextRetry = getNextRetryAtMs(accountId, type)
        val nextScheduled = when {
            type.isChangeDrivenOnly -> 0L
            nextRetry > 0L && failures > 0 -> nextRetry
            lastSuccess > 0L && intervalMs in 1L until Long.MAX_VALUE -> lastSuccess + intervalMs
            else -> 0L
        }
        return ContentSyncStatus(
            type = type,
            lastSuccessMs = lastSuccess,
            lastAttemptMs = lastAttempt,
            nextScheduledMs = nextScheduled,
            isRunning = isRunning,
            consecutiveFailures = failures,
            nextRetryAtMs = nextRetry,
            lastError = getLastError(accountId, type),
            lastOutcome = getLastOutcome(accountId, type)
        )
    }

    companion object {
        const val PREFS_NAME = "kras_smart_sync_prefs"
        const val BASE_BACKOFF_MS = 5L * 60L * 1000L // 5 minutes
        const val MAX_BACKOFF_MS = 6L * 60L * 60L * 1000L // 6 hours

        fun computeExponentialBackoffMs(consecutiveFailures: Int): Long {
            val clamped = consecutiveFailures.coerceIn(1, 10)
            val multiplier = 1L shl (clamped - 1)
            val candidate = BASE_BACKOFF_MS * multiplier
            return candidate.coerceAtMost(MAX_BACKOFF_MS)
        }

        fun computeCredentialsFingerprint(account: Account): String {
            val payload = "${account.serverUrl.trim().lowercase()}|${account.username.trim()}|${account.password}|${account.isDemo}"
            return try {
                val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
                digest.joinToString("") { "%02x".format(it) }
            } catch (_: Exception) {
                payload.hashCode().toString()
            }
        }
    }
}
