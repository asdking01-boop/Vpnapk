package com.example.data.api

/**
 * Cast member returned by TMDb credits, including high-resolution actor portrait URL when available.
 */
data class TmdbCastMember(
    val id: Int = 0,
    val name: String,
    val character: String = "",
    val profileUrl: String = ""
)

/**
 * Similar or recommended title returned by TMDb for Movie & TV Series Details screens.
 */
data class TmdbSimilarItem(
    val tmdbId: Int,
    val title: String,
    val originalTitle: String = "",
    val posterUrl: String = "",
    val backdropUrl: String = "",
    val releaseYear: String = "",
    val rating: Double = 0.0,
    val isSeries: Boolean = false
)

/**
 * Search candidate used for smart automated verification and user-driven manual TMDb matching.
 */
data class TmdbSearchCandidate(
    val tmdbId: Int,
    val title: String,
    val originalTitle: String = "",
    val releaseYear: String = "",
    val releaseDate: String = "",
    val overview: String = "",
    val posterUrl: String = "",
    val backdropUrl: String = "",
    val rating: Double = 0.0,
    val genreIds: List<Int> = emptyList(),
    val isSeries: Boolean = false,
    val confidenceScore: Double = 0.0
)

enum class TmdbDiagnosticStatus {
    IDLE,
    RUNNING,
    PASSED,
    WARNING,
    FAILED,
    NOT_CONFIGURED
}

data class TmdbDiagnosticCheck(
    val status: TmdbDiagnosticStatus = TmdbDiagnosticStatus.IDLE,
    val summary: String = "",
    val httpCode: Int = 0,
    val latencyMs: Long = 0L
)

/**
 * Full diagnostics report displayed in Settings -> TMDb -> Connection Diagnostics.
 * All error strings are strictly sanitized so API keys, Bearer tokens, and session tokens
 * are never exposed in UI or logs.
 */
data class TmdbDiagnosticsReport(
    val isRunning: Boolean = false,
    val apiConnection: TmdbDiagnosticCheck = TmdbDiagnosticCheck(),
    val proxyConnection: TmdbDiagnosticCheck = TmdbDiagnosticCheck(),
    val accountAuth: TmdbDiagnosticCheck = TmdbDiagnosticCheck(),
    val movieSearchTest: TmdbDiagnosticCheck = TmdbDiagnosticCheck(),
    val tvSearchTest: TmdbDiagnosticCheck = TmdbDiagnosticCheck(),
    val imageLoadingTest: TmdbDiagnosticCheck = TmdbDiagnosticCheck(),
    val lastSuccessfulRequestMs: Long = 0L,
    val lastErrorMessage: String = "",
    val checkedAtMs: Long = 0L,
    val cachedMetadataCount: Int = 0,
    val manualMatchCount: Int = 0,
    val isRefreshingMetadata: Boolean = false,
    val refreshFeedbackMessage: String = ""
)

/**
 * Redacts API keys, Bearer tokens, session IDs, and request tokens from any error string
 * before displaying in Connection Diagnostics or storing in telemetry.
 */
object TmdbErrorSanitizer {
    private val QUERY_SECRET_REGEX = Regex(
        """(?i)(api_key|session_id|request_token|access_token|token|password|authorization)=([^&\s"']+)"""
    )
    private val BEARER_REGEX = Regex("""(?i)Bearer\s+[A-Za-z0-9._\-]+""")
    private val JWT_REGEX = Regex("""eyJ[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{6,}""")
    private val HEX_KEY_REGEX = Regex("""\b[0-9a-fA-F]{32,64}\b""")

    fun sanitize(raw: String?, maxLength: Int = 220): String {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return ""
        var cleaned = QUERY_SECRET_REGEX.replace(text) { m -> "${m.groupValues[1]}=[REDACTED]" }
        cleaned = BEARER_REGEX.replace(cleaned, "Bearer [REDACTED]")
        cleaned = JWT_REGEX.replace(cleaned, "[REDACTED_TOKEN]")
        cleaned = HEX_KEY_REGEX.replace(cleaned, "[REDACTED_KEY]")
        cleaned = cleaned.replace(Regex("""\s+"""), " ").trim()
        return if (cleaned.length > maxLength) cleaned.take(maxLength) + "…" else cleaned
    }
}
