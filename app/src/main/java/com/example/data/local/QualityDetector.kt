package com.example.data.local

import com.example.data.model.MediaItem
import kotlin.math.roundToInt

/**
 * Detects and formats resolution/quality badges and optional extra tags
 * ("8K", "4K UHD", "FHD", "HD", "SD", plus "HEVC", "50 FPS", "60 FPS", "5.1").
 */
object QualityDetector {

    data class ParsedQuality(
        val badge: String,          // "8K", "4K UHD", "FHD", "HD", "SD", or "" if unknown
        val extraTags: List<String> // Subset of ["HEVC", "50 FPS", "60 FPS", "5.1"]
    ) {
        val rank: Int
            get() = rankForBadge(badge)

        fun toStorageString(): String {
            val parts = mutableListOf<String>()
            if (badge.isNotBlank()) parts.add(badge)
            parts.addAll(extraTags.distinct())
            return parts.joinToString(" | ")
        }
    }

    // Whole-word/token patterns (case-insensitive, non-alphanumeric boundaries)
    private val REGEX_8K = Regex("""(?:^|[^A-Za-z0-9])(8K|4320P?)(?=$|[^A-Za-z0-9])""", RegexOption.IGNORE_CASE)
    private val REGEX_4K = Regex("""(?:^|[^A-Za-z0-9])(4K|UHD|2160P?)(?=$|[^A-Za-z0-9])""", RegexOption.IGNORE_CASE)
    private val REGEX_FHD = Regex("""(?:^|[^A-Za-z0-9])(FHD|1080P?|FULL[\s_\-\.]*HD)(?=$|[^A-Za-z0-9])""", RegexOption.IGNORE_CASE)
    private val REGEX_HD = Regex("""(?:^|[^A-Za-z0-9])(HD|720P?)(?=$|[^A-Za-z0-9])""", RegexOption.IGNORE_CASE)
    private val REGEX_SD = Regex("""(?:^|[^A-Za-z0-9])(SD|480P?|360P?)(?=$|[^A-Za-z0-9])""", RegexOption.IGNORE_CASE)

    private val REGEX_HEVC = Regex("""(?:^|[^A-Za-z0-9])(HEVC|H[\.\-_]?265)(?=$|[^A-Za-z0-9])""", RegexOption.IGNORE_CASE)
    private val REGEX_H264 = Regex("""(?:^|[^A-Za-z0-9])(H[\.\-_]?264|AVC)(?=$|[^A-Za-z0-9])""", RegexOption.IGNORE_CASE)
    private val REGEX_60FPS = Regex("""(?:^|[^A-Za-z0-9])60[\s_\-]*FPS(?=$|[^A-Za-z0-9])""", RegexOption.IGNORE_CASE)
    private val REGEX_50FPS = Regex("""(?:^|[^A-Za-z0-9])50[\s_\-]*FPS(?=$|[^A-Za-z0-9])""", RegexOption.IGNORE_CASE)
    private val REGEX_5_1 = Regex("""(?:^|[^A-Za-z0-9\.])(?:DD[\s_\-]*|AC3[\s_\-]*|DOLBY[\s_\-]*)?5\.1(?=$|[^A-Za-z0-9\.])""", RegexOption.IGNORE_CASE)

    /**
     * Step 2a: Detect quality badge and optional extra tags from item name and category name.
     * Matches whole tokens only so "4K" inside another word does not match.
     * Returns empty string if nothing is known.
     */
    fun detectFromNameAndCategory(
        name: String?,
        categoryName: String? = null,
        existingHint: String? = null
    ): String {
        return try {
            val safeName = name?.trim().orEmpty()
            val safeCat = categoryName?.trim().orEmpty()
            val safeHint = existingHint?.trim().orEmpty()

            // First try detecting main resolution badge from name, then category, then explicit hint
            val badgeFromName = detectMainBadgeFromText(safeName)
            val badgeFromCat = if (badgeFromName.isEmpty()) detectMainBadgeFromText(safeCat) else ""
            val badgeFromHint = if (badgeFromName.isEmpty() && badgeFromCat.isEmpty()) {
                detectMainBadgeFromText(safeHint)
            } else ""

            val mainBadge = when {
                badgeFromName.isNotEmpty() -> badgeFromName
                badgeFromCat.isNotEmpty() -> badgeFromCat
                else -> badgeFromHint
            }

            val combinedText = "$safeName $safeCat $safeHint"
            val extraTags = detectExtraTagsFromText(combinedText)

            ParsedQuality(badge = mainBadge, extraTags = extraTags).toStorageString()
        } catch (e: Exception) {
            ""
        }
    }

    fun detectMainBadgeFromText(text: String): String {
        if (text.isBlank()) return ""
        return when {
            REGEX_8K.containsMatchIn(text) -> "8K"
            REGEX_4K.containsMatchIn(text) -> "4K UHD"
            REGEX_FHD.containsMatchIn(text) -> "FHD"
            REGEX_HD.containsMatchIn(text) -> "HD"
            REGEX_SD.containsMatchIn(text) -> "SD"
            else -> ""
        }
    }

    fun detectExtraTagsFromText(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val tags = mutableListOf<String>()
        if (REGEX_HEVC.containsMatchIn(text)) {
            tags.add("HEVC")
        }
        when {
            REGEX_60FPS.containsMatchIn(text) -> tags.add("60 FPS")
            REGEX_50FPS.containsMatchIn(text) -> tags.add("50 FPS")
        }
        if (REGEX_5_1.containsMatchIn(text)) {
            tags.add("5.1")
        }
        return tags
    }

    fun hasH264Token(text: String): Boolean {
        return text.isNotBlank() && REGEX_H264.containsMatchIn(text)
    }

    /**
     * Maps real video height to canonical badge:
     * height >= 4320 -> "8K"
     * height >= 2160 -> "4K UHD"
     * height >= 1080 -> "FHD"
     * height >= 720  -> "HD"
     * height > 0     -> "SD"
     */
    fun mapHeightToBadge(height: Int): String {
        return when {
            height >= 4320 -> "8K"
            height >= 2160 -> "4K UHD"
            height >= 1080 -> "FHD"
            height >= 720 -> "HD"
            height > 0 -> "SD"
            else -> ""
        }
    }

    /**
     * Step 2b & 2c: Build canonical quality storage string from real video height, codec, framerate, and audio channels.
     * Real detected height overrides any name-based guess.
     */
    fun buildFromRealSpecs(
        height: Int,
        frameRate: Float = 0f,
        codecOrMime: String? = null,
        audioChannels: Int = 0,
        audioLayout: String? = null,
        fallbackQuality: String = ""
    ): String {
        return try {
            val prev = parseStoredQuality(fallbackQuality)
            val realBadge = mapHeightToBadge(height).ifEmpty { prev.badge }

            val tags = mutableListOf<String>()
            val codecStr = codecOrMime.orEmpty().lowercase()
            if (codecStr.contains("hevc") || codecStr.contains("h265") || codecStr.contains("h.265") ||
                codecStr.contains("hev1") || codecStr.contains("hvc1") || prev.extraTags.contains("HEVC")
            ) {
                tags.add("HEVC")
            }

            val fpsInt = if (frameRate > 0f) frameRate.roundToInt() else 0
            when {
                fpsInt in 58..65 -> tags.add("60 FPS")
                fpsInt in 48..54 -> tags.add("50 FPS")
                prev.extraTags.contains("60 FPS") -> tags.add("60 FPS")
                prev.extraTags.contains("50 FPS") -> tags.add("50 FPS")
            }

            if (audioChannels >= 6 || audioLayout?.contains("5.1") == true || prev.extraTags.contains("5.1")) {
                tags.add("5.1")
            }

            ParsedQuality(badge = realBadge, extraTags = tags).toStorageString()
        } catch (e: Exception) {
            fallbackQuality
        }
    }

    /**
     * Parses a stored quality string (e.g. "4K UHD | HEVC | 50 FPS" or "FHD" or "")
     * into its main badge and extra tags.
     */
    fun parseStoredQuality(raw: String?): ParsedQuality {
        if (raw.isNullOrBlank()) return ParsedQuality("", emptyList())
        return try {
            val tokens = raw.split("|", "•", ",").map { it.trim() }.filter { it.isNotEmpty() }
            var mainBadge = ""
            val extras = mutableListOf<String>()

            for (token in tokens) {
                val upper = token.uppercase()
                when {
                    upper == "8K" -> if (mainBadge.isEmpty()) mainBadge = "8K"
                    upper == "4K UHD" || upper == "4K" || upper == "UHD" -> if (mainBadge.isEmpty()) mainBadge = "4K UHD"
                    upper == "FHD" || upper == "1080P" || upper == "FULL HD" -> if (mainBadge.isEmpty()) mainBadge = "FHD"
                    upper == "HD" || upper == "720P" -> if (mainBadge.isEmpty()) mainBadge = "HD"
                    upper == "SD" || upper == "480P" || upper == "360P" -> if (mainBadge.isEmpty()) mainBadge = "SD"
                    upper == "HEVC" || upper == "H265" || upper == "H.265" -> extras.add("HEVC")
                    upper == "60 FPS" || upper == "60FPS" -> extras.add("60 FPS")
                    upper == "50 FPS" || upper == "50FPS" -> extras.add("50 FPS")
                    upper == "5.1" -> extras.add("5.1")
                    else -> {
                        // Fallback if raw string wasn't pre-normalized
                        val detectedMain = detectMainBadgeFromText(token)
                        if (detectedMain.isNotEmpty() && mainBadge.isEmpty()) {
                            mainBadge = detectedMain
                        }
                        extras.addAll(detectExtraTagsFromText(token))
                    }
                }
            }
            ParsedQuality(badge = mainBadge, extraTags = extras.distinct())
        } catch (e: Exception) {
            ParsedQuality("", emptyList())
        }
    }

    /**
     * Numeric rank for sorting by quality (highest first):
     * 8K -> 5, 4K UHD -> 4, FHD -> 3, HD -> 2, SD -> 1, unknown -> 0
     */
    fun rankForBadge(badgeOrStored: String?): Int {
        if (badgeOrStored.isNullOrBlank()) return 0
        val head = badgeOrStored.substringBefore("|").trim().uppercase()
        return when (head) {
            "8K" -> 5
            "4K UHD", "4K" -> 4
            "FHD", "1080P" -> 3
            "HD", "720P" -> 2
            "SD", "480P", "360P" -> 1
            else -> {
                val parsed = parseStoredQuality(badgeOrStored)
                when (parsed.badge) {
                    "8K" -> 5
                    "4K UHD" -> 4
                    "FHD" -> 3
                    "HD" -> 2
                    "SD" -> 1
                    else -> 0
                }
            }
        }
    }

    /**
     * Matches an item's quality against a filter chip ("All", "8K", "4K", "FHD", "HD", "SD").
     */
    fun matchesQualityFilter(storedQuality: String?, filter: String): Boolean {
        if (filter.isBlank() || filter.equals("All", ignoreCase = true)) return true
        val badge = parseStoredQuality(storedQuality).badge
        return when (filter.uppercase()) {
            "8K" -> badge == "8K"
            "4K", "4K UHD" -> badge == "4K UHD" || badge == "8K" && filter.equals("8K", ignoreCase = true)
            "FHD" -> badge == "FHD"
            "HD" -> badge == "HD"
            "SD" -> badge == "SD"
            else -> true
        }
    }

    /**
     * Formats real playback resolution + frame rate for the player overlay, e.g. "1080p 50 FPS".
     */
    fun formatPlayerResolutionOverlay(height: Int, frameRate: Float): String {
        if (height <= 0) return ""
        val resPart = "${height}p"
        val fpsInt = if (frameRate > 0f) frameRate.roundToInt() else 0
        return if (fpsInt >= 24) {
            "$resPart $fpsInt FPS"
        } else {
            resPart
        }
    }

    /**
     * Parses Xtream avg_frame_rate / r_frame_rate strings such as "50/1", "60000/1001", or "50".
     */
    fun parseFrameRateString(fpsRaw: String?): Float {
        if (fpsRaw.isNullOrBlank()) return 0f
        return try {
            if (fpsRaw.contains("/")) {
                val parts = fpsRaw.split("/")
                val num = parts[0].trim().toFloatOrNull() ?: 0f
                val den = parts.getOrNull(1)?.trim()?.toFloatOrNull() ?: 1f
                if (den > 0f) num / den else 0f
            } else {
                fpsRaw.trim().toFloatOrNull() ?: 0f
            }
        } catch (e: Exception) {
            0f
        }
    }

    fun sortMediaByQualityDescending(items: List<MediaItem>): List<MediaItem> {
        return items.sortedWith(
            Comparator { a, b ->
                val rankA = rankForBadge(a.quality.ifBlank { a.resolution })
                val rankB = rankForBadge(b.quality.ifBlank { b.resolution })
                val cmpRank = rankB.compareTo(rankA)
                if (cmpRank != 0) cmpRank else a.name.compareTo(b.name, ignoreCase = true)
            }
        )
    }
}
