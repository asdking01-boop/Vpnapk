package com.example.data.api

import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Shared title-cleaning, strict TMDb match scoring, and plot synopsis validation utilities.
 *
 * Guarantees:
 * - Strips IPTV category/country/provider prefixes, season/episode markers, emojis, and quality tags.
 * - Rejects generic placeholder text, empty strings, URLs, or title-only echoes as invalid synopses.
 * - Strictly verifies candidate TMDb matches by cleaned title similarity, media type, and release year
 *   so an overview from a different movie or series is never displayed.
 */
object TmdbMetadataMatcher {

    private val LEADING_IPTV_PREFIX_REGEX = Regex(
        """^(?:(?:[A-Z]{2,4}|EN|US|UK|AR|FR|DE|ES|IT|TR|RU|NL|PT|BR|IN|CA|AU|VIP|VOD|4K|UHD|FHD|HD|SD|HEVC|TOP|NEW|MULTI|NF|NETFLIX|DISNEY\+?|HBO|MAX|OSN|SHAHID|MBC|BEIN|PRIME|AMAZON|APPLE\s*TV\+?|STARZ|HULU|PARAMOUNT\+?|PEACOCK|SHOWTIME|ANIME|KIDS|MOVIES?|SERIES|FILMS?|ARABIC|ENGLISH|TURKISH|HINDI|FRENCH|SPANISH|ACTION|DRAMA|COMEDY|HORROR|SCIFI|SCI-FI|DOCUMENTARY|أفلام|افلام|مسلسلات|عربي|أجنبي|اجنبي|نتفلكس|شاهد|انمي|كرتون)\s*(?:\||:|-(?=\s)|•|\/)\s*|[\p{So}\uFE0F\u200D]+\s*[\|\-:]?\s*)+""",
        RegexOption.IGNORE_CASE
    )

    private val BRACKETED_YEAR_REGEX = Regex("""[\(\[](19\d{2}|20[0-3]\d)[\)\]]""")
    private val TRAILING_YEAR_REGEX = Regex("""(?:\s+|-|\||\.)\s*(19\d{2}|20[0-3]\d)\s*$""")
    private val INLINE_YEAR_REGEX = Regex("""(?<=[\s._\-])(19\d{2}|20[0-3]\d)(?=[\s._\-]|$)""")
    private val BARE_YEAR_ONLY_REGEX = Regex("""^(19\d{2}|20[0-3]\d)$""")

    private val SEASON_EPISODE_TAG_REGEX = Regex(
        """\b(?:S\d{1,2}\s*E\d{1,3}|S\d{1,2}|Season\s*\d{1,2}|Episode\s*\d{1,3}|Ep\.?\s*\d{1,3})\b.*$""",
        RegexOption.IGNORE_CASE
    )
    private val ARABIC_SEASON_EPISODE_REGEX = Regex("""(?:الموسم|الجزء|الحلقة)\s*\d+.*$""")

    private val QUALITY_AND_RELEASE_TAGS_REGEX = Regex(
        """\b(?:8K|4K|UHD|2160p|1080p|1080i|FHD|Full\s*HD|720p|HD|480p|SD|HEVC|H\.?265|H\.?264|x264|x265|AV1|10bit|HDR10\+?|HDR|DV|Dolby\s*Vision|Dolby\s*Atmos|Atmos|TrueHD|DDP?5\.?1|DTS(?:-?HD)?|AAC|5\.1|7\.1|50\s*FPS|60\s*FPS|BluRay|Blu-Ray|BDRip|BRRip|WEB-?DL|WEBRip|WEB|HDRip|DVDRip|HDCAM|CAMRip|CAM|HDTS|TS|TC|HC|REMUX|PROPER|REPACK|EXTENDED|UNRATED|MULTI-?SUB|MULTI|DUAL|SUB|SUBBED|DUB|DUBBED|VOSTFR|VF|VO|ENG|ARB|ARABIC\s*SUB)\b""",
        RegexOption.IGNORE_CASE
    )
    private val ARABIC_RELEASE_TAGS_REGEX = Regex("""(?:مترجم|مدبلج|حصري|جديد|كامل|بجودة\s*عالية|عالي\s*الجودة|نسخة\s*اصلية|نسخة\s*أصلية)""")

    private val BRACKET_NOISE_REGEX = Regex("""\[[^\]]*\]|\([^\)]*\)""")
    private val EMOJI_AND_SYMBOLS_REGEX = Regex("""[\p{So}\uFE0F\u200D]+""")
    private val HTML_TAG_REGEX = Regex("""<[^>]+>""")
    private val MULTI_SPACE_REGEX = Regex("""\s{2,}""")
    private val NON_ALNUM_REGEX = Regex("""[^\p{L}\p{N}\s]""")
    private val DIACRITICS_REGEX = Regex("""\p{InCombiningDiacriticalMarks}+""")
    private val ARABIC_TASHKEEL_REGEX = Regex("""[\u0610-\u061A\u064B-\u065F\u0670\u06D6-\u06ED\u0640]""")
    private val ARABIC_CHAR_REGEX = Regex("""[\u0600-\u06FF]""")
    private val LATIN_CHAR_REGEX = Regex("""[A-Za-z]""")
    private val URL_ONLY_REGEX = Regex("""^https?://\S+$""", RegexOption.IGNORE_CASE)
    private val BILINGUAL_SPLIT_REGEX = Regex("""\s*(?:\||\/|—|–|\s-\s)\s*""")
    private val DOT_OR_UNDERSCORE_RELEASE_REGEX = Regex("""(?:^|\s)[^\s]*(?:\.[^\s]*){2,}""")

    /**
     * Known generic / placeholder / boilerplate phrases that must never be treated as real plot synopses.
     */
    private val GENERIC_PLACEHOLDER_PATTERNS: List<Regex> = listOf(
        // Previous hardcoded KRAS TV placeholders
        Regex("""experience an extraordinary cinematic story""", RegexOption.IGNORE_CASE),
        Regex("""an extraordinary story unfolding across space and time""", RegexOption.IGNORE_CASE),
        Regex("""استمتع بمشاهدة هذا الفيلم بجودة عالية""", RegexOption.IGNORE_CASE),
        Regex("""رحلة درامية مشوقة وصراعات مليئة بالإثارة والغموض""", RegexOption.IGNORE_CASE),

        // Common IPTV / Xtream / TMDB missing-data placeholders
        Regex("""^(?:n/?a|none|null|nil|unknown|tba|tbd|coming\s+soon|not\s+available|unavailable|-+|\.+|\?+)$""", RegexOption.IGNORE_CASE),
        Regex("""^(?:no\s+)?(?:plot|overview|synopsis|description|summary|info|information|details?)\s*(?:is\s*)?(?:not\s*)?(?:available|found|provided|yet|added)?\.?$""", RegexOption.IGNORE_CASE),
        Regex("""^story\s+synopsis\s+not\s+available\.?$""", RegexOption.IGNORE_CASE),
        Regex("""^we\s+don'?t\s+have\s+an\s+overview\s+translated""", RegexOption.IGNORE_CASE),
        Regex("""^lorem\s+ipsum""", RegexOption.IGNORE_CASE),
        Regex("""^(?:watch|stream|download)\s+.{0,80}(?:online|free|full\s+movie|hd|4k|1080p)""", RegexOption.IGNORE_CASE),
        Regex("""^(?:enjoy\s+watching|watch\s+in\s+hd|high\s+quality\s+stream)""", RegexOption.IGNORE_CASE),
        Regex("""^(?:ملخص\s+القصة\s+غير\s+متوفر|لا\s+يوجد\s+(?:وصف|ملخص|قصة)|لا\s+توجد\s+(?:قصة|معلومات|تفاصيل)|غير\s+متوفر|قريبا|قريباً)\.?$""")
    )

    /**
     * Cleans an IPTV movie or series title for accurate TMDb lookup, removing category labels,
     * country/provider tags, quality/codec markers, emojis, and season/episode suffixes while
     * preserving the core title and extracting any 4-digit release year.
     */
    fun cleanIptvTitle(rawName: String): CleanedTmdbQuery {
        var working = rawName.trim()
        if (working.isEmpty()) return CleanedTmdbQuery("", "")

        var extractedYear = ""
        val bracketYearMatch = BRACKETED_YEAR_REGEX.find(working)
        if (bracketYearMatch != null) {
            extractedYear = bracketYearMatch.groupValues[1]
        }

        // Strip emojis and leading IPTV category / provider / language prefixes first
        working = EMOJI_AND_SYMBOLS_REGEX.replace(working, " ").trim()
        working = LEADING_IPTV_PREFIX_REGEX.replace(working, "").trim()

        // Normalize scene-style dot/underscore filenames (e.g. "Inception.2010.1080p.BluRay" or after "VIP | EN - ")
        if (working.contains('_') && !working.contains(' ')) {
            working = working.replace('_', ' ')
        } else if (DOT_OR_UNDERSCORE_RELEASE_REGEX.containsMatchIn(working) && !working.contains(' ')) {
            working = working.replace('.', ' ')
        }

        // Strip season/episode markers (e.g. S01E03, Season 2, الموسم 1)
        working = SEASON_EPISODE_TAG_REGEX.replace(working, "")
        working = ARABIC_SEASON_EPISODE_REGEX.replace(working, "")

        // Remove bracketed/parenthesized metadata blocks like [1080p], [MULTI-SUB], (2024), (DUB)
        working = BRACKET_NOISE_REGEX.replace(working, " ")

        // If year was inline right before quality tags (e.g. "Inception 2010 1080p BluRay"), extract it before stripping
        if (extractedYear.isEmpty() && !BARE_YEAR_ONLY_REGEX.matches(working.trim())) {
            val inlineMatches = INLINE_YEAR_REGEX.findAll(working).toList()
            val lastInline = inlineMatches.lastOrNull()
            if (lastInline != null && lastInline.range.first > 0) {
                val beforeYear = working.substring(0, lastInline.range.first).trim()
                val afterYear = working.substring(lastInline.range.last + 1).trim()
                if (beforeYear.isNotEmpty() && (afterYear.isEmpty() || QUALITY_AND_RELEASE_TAGS_REGEX.containsMatchIn(afterYear))) {
                    extractedYear = lastInline.groupValues[1]
                    working = beforeYear
                }
            }
        }

        // Remove quality, codec, rip, and dub/sub tags
        working = QUALITY_AND_RELEASE_TAGS_REGEX.replace(working, " ")
        working = ARABIC_RELEASE_TAGS_REGEX.replace(working, " ")

        // Normalize separators like pipes left after tag removal
        val trimmedBeforeYear = working
            .replace(Regex("""\s*\|\s*$"""), "")
            .trim()
            .trimEnd('-', '|', ':', '.', '_')
            .trim()

        if (extractedYear.isEmpty()) {
            // Only extract trailing year if there is a non-empty title before it (protects titles like "1917" or "2012")
            if (!BARE_YEAR_ONLY_REGEX.matches(trimmedBeforeYear)) {
                val trailingYearMatch = TRAILING_YEAR_REGEX.find(trimmedBeforeYear)
                if (trailingYearMatch != null) {
                    val candidateYear = trailingYearMatch.groupValues[1]
                    val withoutYear = trimmedBeforeYear.substring(0, trailingYearMatch.range.first).trim()
                    if (withoutYear.isNotEmpty()) {
                        extractedYear = candidateYear
                        working = withoutYear
                    } else {
                        working = trimmedBeforeYear
                    }
                } else {
                    working = trimmedBeforeYear
                }
            } else {
                working = trimmedBeforeYear
            }
        } else {
            val stripped = trimmedBeforeYear.removeSuffix(extractedYear).trim()
            working = if (stripped.isNotEmpty()) stripped else trimmedBeforeYear
        }

        // Re-apply leading prefix strip once more in case a bracketed tag preceded a prefix
        working = LEADING_IPTV_PREFIX_REGEX.replace(working.trim(), "")

        val finalTitle = MULTI_SPACE_REGEX.replace(working, " ")
            .trim()
            .trim('-', '|', ':', '.', '_')
            .trim()

        return CleanedTmdbQuery(
            cleanTitle = finalTitle.ifEmpty { rawName.trim() },
            extractedYear = extractedYear
        )
    }

    /**
     * Generates prioritized search query variants from an IPTV title, supporting bilingual
     * titles such as "The Matrix | الماتريكس (1999) 4K" or "Avatar - أفاتار" so TMDb can be
     * searched by original, English, and Arabic title variants.
     */
    fun buildSearchQueries(rawName: String): List<String> {
        val cleaned = cleanIptvTitle(rawName).cleanTitle
        if (cleaned.isBlank()) return emptyList()

        val variants = LinkedHashSet<String>()
        val parts = BILINGUAL_SPLIT_REGEX.split(cleaned)
            .map { it.trim().trim('-', '|', ':', '.', '_') }
            .filter { it.length >= 2 }

        if (parts.size >= 2) {
            val hasArabicPart = parts.any { containsArabicScript(it) }
            val hasLatinPart = parts.any { LATIN_CHAR_REGEX.containsMatchIn(it) }
            if (hasArabicPart && hasLatinPart) {
                // Prioritize individual script segments first (English/Latin and Arabic), then full string
                parts.filter { LATIN_CHAR_REGEX.containsMatchIn(it) && !containsArabicScript(it) }
                    .forEach { variants.add(it) }
                parts.filter { containsArabicScript(it) }
                    .forEach { variants.add(it) }
                variants.add(cleaned)
                return variants.toList()
            }
        }

        variants.add(cleaned)
        if (parts.size >= 2 && cleaned.contains("|")) {
            parts.forEach { variants.add(it) }
        }
        return variants.toList()
    }

    /**
     * Normalizes a TMDb or Xtream image path into a full HTTPS URL, resolving relative TMDb paths
     * like "/8ZTVqvKDQ8emSGUEMjsS4yHAwrp.jpg" and rejecting null/placeholder literals.
     */
    fun normalizeTmdbImageUrl(raw: String?, size: String = "w1280"): String {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return ""
        val lower = trimmed.lowercase()
        if (lower == "null" || lower == "none" || lower == "n/a" || lower == "undefined") return ""
        if (trimmed.startsWith("https://", ignoreCase = true) || trimmed.startsWith("http://", ignoreCase = true)) {
            return trimmed
        }
        if (trimmed.startsWith("//")) {
            return "https:$trimmed"
        }
        if (trimmed.startsWith("/") && (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp"))) {
            return "https://image.tmdb.org/t/p/$size$trimmed"
        }
        return ""
    }

    /**
     * Returns true ONLY when [backdropUrl] is a genuine, usable widescreen backdrop URL
     * and NOT a blank string, invalid literal, or a copy of the portrait [posterUrl].
     */
    fun isValidBackdropUrl(backdropUrl: String?, posterUrl: String? = null): Boolean {
        val normalizedBackdrop = normalizeTmdbImageUrl(backdropUrl, size = "w1280")
        if (normalizedBackdrop.isBlank()) return false
        val normalizedPoster = normalizeTmdbImageUrl(posterUrl, size = "w500")
        if (normalizedPoster.isNotBlank() && normalizedBackdrop.equals(normalizedPoster, ignoreCase = true)) {
            return false
        }
        return true
    }

    private val TMDB_GENRES_EN = mapOf(
        28 to "Action", 12 to "Adventure", 16 to "Animation", 35 to "Comedy",
        80 to "Crime", 99 to "Documentary", 18 to "Drama", 10751 to "Family",
        14 to "Fantasy", 36 to "History", 27 to "Horror", 10402 to "Music",
        9648 to "Mystery", 10749 to "Romance", 878 to "Science Fiction",
        10770 to "TV Movie", 53 to "Thriller", 10752 to "War", 37 to "Western",
        10759 to "Action & Adventure", 10762 to "Kids", 10763 to "News",
        10764 to "Reality", 10765 to "Sci-Fi & Fantasy", 10766 to "Soap",
        10767 to "Talk", 10768 to "War & Politics"
    )

    private val TMDB_GENRES_AR = mapOf(
        28 to "أكشن", 12 to "مغامرة", 16 to "رسوم متحركة", 35 to "كوميديا",
        80 to "جريمة", 99 to "وثائقي", 18 to "دراما", 10751 to "عائلي",
        14 to "فانتازيا", 36 to "تاريخي", 27 to "رعب", 10402 to "موسيقى",
        9648 to "غموض", 10749 to "رومانسي", 878 to "خيال علمي",
        10770 to "فيلم تلفزيوني", 53 to "إثارة", 10752 to "حرب", 37 to "غربي",
        10759 to "أكشن ومغامرة", 10762 to "أطفال", 10763 to "أخبار",
        10764 to "واقعي", 10765 to "خيال علمي وفانتازيا", 10766 to "دراما يومية",
        10767 to "حواري", 10768 to "حرب وسياسة"
    )

    const val TMDB_LANG_ARABIC = "ar-SA"
    const val TMDB_LANG_ENGLISH = "en-US"

    fun mapTmdbGenreIds(ids: List<Int>, preferArabic: Boolean = false): String {
        if (ids.isEmpty()) return ""
        val table = if (preferArabic) TMDB_GENRES_AR else TMDB_GENRES_EN
        val sep = if (preferArabic) "، " else ", "
        return ids.mapNotNull { table[it] }.distinct().take(4).joinToString(sep)
    }

    private val GENRE_EN_TO_AR: Map<String, String> = mapOf(
        "action" to "أكشن",
        "adventure" to "مغامرة",
        "action & adventure" to "أكشن ومغامرة",
        "animation" to "رسوم متحركة",
        "anime" to "أنمي",
        "comedy" to "كوميديا",
        "crime" to "جريمة",
        "documentary" to "وثائقي",
        "drama" to "دراما",
        "family" to "عائلي",
        "fantasy" to "فانتازيا",
        "history" to "تاريخي",
        "historical" to "تاريخي",
        "horror" to "رعب",
        "music" to "موسيقى",
        "musical" to "موسيقي",
        "mystery" to "غموض",
        "romance" to "رومانسي",
        "romantic" to "رومانسي",
        "science fiction" to "خيال علمي",
        "sci-fi" to "خيال علمي",
        "scifi" to "خيال علمي",
        "sci-fi & fantasy" to "خيال علمي وفانتازيا",
        "tv movie" to "فيلم تلفزيوني",
        "thriller" to "إثارة",
        "war" to "حرب",
        "war & politics" to "حرب وسياسة",
        "western" to "غربي",
        "kids" to "أطفال",
        "news" to "أخبار",
        "reality" to "واقعي",
        "soap" to "دراما يومية",
        "talk" to "حواري",
        "biography" to "سيرة ذاتية",
        "sport" to "رياضة",
        "sports" to "رياضة",
        "superhero" to "أبطال خارقون"
    )

    private val GENRE_AR_TO_EN: Map<String, String> = mapOf(
        "أكشن" to "Action",
        "اكشن" to "Action",
        "حركة" to "Action",
        "مغامرة" to "Adventure",
        "مغامرات" to "Adventure",
        "أكشن ومغامرة" to "Action & Adventure",
        "رسوم متحركة" to "Animation",
        "أنمي" to "Anime",
        "انمي" to "Anime",
        "كوميديا" to "Comedy",
        "كوميدي" to "Comedy",
        "جريمة" to "Crime",
        "وثائقي" to "Documentary",
        "دراما" to "Drama",
        "عائلي" to "Family",
        "عائلة" to "Family",
        "فانتازيا" to "Fantasy",
        "خيال" to "Fantasy",
        "تاريخي" to "History",
        "تاريخ" to "History",
        "رعب" to "Horror",
        "موسيقى" to "Music",
        "موسيقي" to "Musical",
        "غموض" to "Mystery",
        "رومانسي" to "Romance",
        "رومانسية" to "Romance",
        "خيال علمي" to "Science Fiction",
        "خيال علمي وفانتازيا" to "Sci-Fi & Fantasy",
        "فيلم تلفزيوني" to "TV Movie",
        "إثارة" to "Thriller",
        "اثارة" to "Thriller",
        "تشويق" to "Thriller",
        "حرب" to "War",
        "حرب وسياسة" to "War & Politics",
        "غربي" to "Western",
        "أطفال" to "Kids",
        "اطفال" to "Kids",
        "أخبار" to "News",
        "واقعي" to "Reality",
        "دراما يومية" to "Soap",
        "حواري" to "Talk",
        "سيرة ذاتية" to "Biography",
        "رياضة" to "Sport"
    )

    /**
     * Localizes a comma/slash-separated genre string into Arabic or English while preserving
     * any custom/unmapped genre names intact.
     */
    fun localizeGenres(rawGenres: String?, preferArabic: Boolean): String {
        val raw = rawGenres?.trim().orEmpty()
        if (raw.isEmpty()) return ""
        val tokens = raw.split(",", "،", "/", "|", "•")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return raw

        val mapped = tokens.map { token ->
            if (preferArabic) {
                GENRE_EN_TO_AR[token.lowercase()] ?: token
            } else {
                GENRE_AR_TO_EN[token] ?: token
            }
        }.distinct()

        val sep = if (preferArabic && mapped.any { containsArabicScript(it) }) "، " else ", "
        return mapped.joinToString(sep)
    }

    /**
     * Returns true if [plot] is a genuine, meaningful story synopsis rather than blank,
     * generic placeholder text, a URL, or a bare repetition of [title].
     */
    fun isValidSynopsis(plot: String?, title: String = ""): Boolean {
        val cleaned = cleanSynopsisFormatting(plot)
        if (cleaned.isBlank()) return false
        if (URL_ONLY_REGEX.matches(cleaned)) return false

        // Reject known placeholder / boilerplate phrases
        for (pattern in GENERIC_PLACEHOLDER_PATTERNS) {
            if (pattern.containsMatchIn(cleaned)) return false
        }

        val isArabicText = containsArabicScript(cleaned)
        val minChars = if (isArabicText) 15 else 20
        val minWords = if (isArabicText) 3 else 4
        if (cleaned.length < minChars) return false

        val words = cleaned.split(Regex("""\s+""")).filter { it.isNotBlank() }
        if (words.size < minWords) return false

        // Reject synopses that merely echo the movie/series title (with or without year/tags)
        if (title.isNotBlank()) {
            val normPlot = normalizeForComparison(cleanIptvTitle(cleaned).cleanTitle)
            val normTitle = normalizeForComparison(cleanIptvTitle(title).cleanTitle)
            if (normTitle.isNotEmpty() && (normPlot == normTitle || normPlot == "$normTitle movie" || normPlot == "$normTitle series")) {
                return false
            }
        }

        return true
    }

    /**
     * Cleans HTML/whitespace from a synopsis and returns it if valid, or empty string if invalid/generic.
     */
    fun sanitizeSynopsis(plot: String?, title: String = ""): String {
        val cleaned = cleanSynopsisFormatting(plot)
        return if (isValidSynopsis(cleaned, title)) cleaned else ""
    }

    fun cleanSynopsisFormatting(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val withoutHtml = HTML_TAG_REGEX.replace(raw, " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
        return MULTI_SPACE_REGEX.replace(withoutHtml.replace("\r\n", "\n"), " ").trim()
    }

    fun containsArabicScript(text: String?): Boolean {
        return !text.isNullOrBlank() && ARABIC_CHAR_REGEX.containsMatchIn(text)
    }

    /**
     * Evaluates whether a TMDb search candidate accurately matches the requested title and year.
     *
     * Rules:
     * - If both query year and candidate year are valid 4-digit years, they MUST be within ±1 year
     *   (prevents matching remakes or unrelated titles from different years).
     * - Computes similarity against both localized title and original title.
     * - Prevents sequel/installment mismatches (e.g. "Dune" must not match "Dune: Part Two").
     * - Requires high similarity (>= 0.72 with matching year, >= 0.80 without year).
     */
    fun isConfidentMatch(
        queryTitle: String,
        queryYear: String,
        candidateTitle: String,
        candidateOriginalTitle: String = "",
        candidateYear: String = ""
    ): Boolean {
        val score = scoreCandidateMatch(
            queryTitle = queryTitle,
            queryYear = queryYear,
            candidateTitle = candidateTitle,
            candidateOriginalTitle = candidateOriginalTitle,
            candidateYear = candidateYear
        )
        return score > 0.0
    }

    /**
     * Returns a confidence score in `(0.0, 1.2]` if the candidate is a confident match,
     * or `0.0` if the candidate should be rejected.
     */
    fun scoreCandidateMatch(
        queryTitle: String,
        queryYear: String,
        candidateTitle: String,
        candidateOriginalTitle: String = "",
        candidateYear: String = ""
    ): Double {
        val queryVariants = buildSearchQueries(queryTitle)
            .map { normalizeForComparison(it) }
            .filter { it.isNotEmpty() }
        if (queryVariants.isEmpty()) return 0.0

        val normCandTitle = normalizeForComparison(candidateTitle)
        val normCandOrig = normalizeForComparison(candidateOriginalTitle)
        if (normCandTitle.isEmpty() && normCandOrig.isEmpty()) return 0.0

        val qYear = extractFourDigitYear(queryYear)
        val cYear = extractFourDigitYear(candidateYear)

        var yearBonus = 0.0
        if (qYear != null && cYear != null) {
            val diff = abs(qYear - cYear)
            if (diff > 1) {
                return 0.0 // Strict year mismatch rejection (protects remakes & similar titles)
            }
            yearBonus = if (diff == 0) 0.12 else 0.04
        }

        var bestSim = 0.0
        for (normQuery in queryVariants) {
            if (normCandTitle.isNotEmpty()) {
                bestSim = max(bestSim, computeTitleSimilarity(normQuery, normCandTitle))
            }
            if (normCandOrig.isNotEmpty()) {
                bestSim = max(bestSim, computeTitleSimilarity(normQuery, normCandOrig))
            }
        }

        val minThreshold = if (qYear != null && cYear != null) 0.72 else 0.80
        if (bestSim < minThreshold) return 0.0

        return bestSim + yearBonus
    }

    internal fun extractFourDigitYear(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        val match = Regex("""\b(19\d{2}|20[0-3]\d)\b""").find(raw)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }

    internal fun normalizeForComparison(raw: String): String {
        if (raw.isBlank()) return ""
        var s = raw.trim().lowercase()
        // Normalize Arabic forms & remove tashkeel
        s = ARABIC_TASHKEEL_REGEX.replace(s, "")
            .replace('أ', 'ا')
            .replace('إ', 'ا')
            .replace('آ', 'ا')
            .replace('ة', 'ه')
            .replace('ى', 'ي')
        // Strip Latin diacritics (e.g. é -> e)
        s = Normalizer.normalize(s, Normalizer.Form.NFD)
        s = DIACRITICS_REGEX.replace(s, "")
        // Normalize ampersand and common sequel number words
        s = s.replace("&", " and ")
            .replace(Regex("""\bpart\s+ii\b"""), "part 2")
            .replace(Regex("""\bpart\s+iii\b"""), "part 3")
            .replace(Regex("""\bpart\s+iv\b"""), "part 4")
            .replace(Regex("""\bpart\s+two\b"""), "part 2")
            .replace(Regex("""\bpart\s+three\b"""), "part 3")
            .replace(Regex("""\bpart\s+one\b"""), "part 1")
        s = NON_ALNUM_REGEX.replace(s, " ")
        return MULTI_SPACE_REGEX.replace(s, " ").trim()
    }

    internal fun computeTitleSimilarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0

        // Check exact match after ignoring leading English article "the"
        val aNoThe = a.removePrefix("the ").trim()
        val bNoThe = b.removePrefix("the ").trim()
        if (aNoThe.isNotEmpty() && aNoThe == bNoThe) return 0.96

        val tokensA = a.split(" ").filter { it.isNotBlank() }
        val tokensB = b.split(" ").filter { it.isNotBlank() }

        // Guard against sequel/number mismatches (e.g. "dune 2" vs "dune", "avatar" vs "avatar 2")
        val digitsA = tokensA.filter { it.all(Char::isDigit) }.toSet()
        val digitsB = tokensB.filter { it.all(Char::isDigit) }.toSet()
        if (digitsA != digitsB) {
            return 0.0
        }

        val setA = tokensA.toSet()
        val setB = tokensB.toSet()
        val intersection = setA.intersect(setB).size
        val union = setA.union(setB).size
        val jaccard = if (union > 0) intersection.toDouble() / union.toDouble() else 0.0

        val maxLen = max(a.length, b.length)
        val editDist = levenshteinDistance(a, b)
        val editSim = 1.0 - (editDist.toDouble() / maxLen.toDouble())

        // Penalize large token-count disparity (e.g. "Avatar" vs "Avatar The Way of Water")
        val tokenRatio = min(tokensA.size, tokensB.size).toDouble() / max(tokensA.size, tokensB.size).toDouble()
        val combined = (jaccard * 0.55) + (editSim * 0.45)

        return if (tokenRatio < 0.6 && jaccard < 0.75) {
            combined * tokenRatio
        } else {
            combined
        }
    }

    private fun levenshteinDistance(s1: String, s2: String): Int {
        val m = s1.length
        val n = s2.length
        if (m == 0) return n
        if (n == 0) return m

        var prev = IntArray(n + 1) { it }
        var curr = IntArray(n + 1)

        for (i in 1..m) {
            curr[0] = i
            val c1 = s1[i - 1]
            for (j in 1..n) {
                val cost = if (c1 == s2[j - 1]) 0 else 1
                curr[j] = min(
                    min(curr[j - 1] + 1, prev[j] + 1),
                    prev[j - 1] + cost
                )
            }
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return prev[n]
    }
}
