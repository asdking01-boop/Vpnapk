package com.example.data.local

import com.example.data.model.FavoriteItem
import com.example.data.model.LiveChannelOrder
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import java.util.Locale

data class NormalizedChannel(
    val groupKey: String,
    val seq: Int,
    val hasNumber: Boolean
)

data class ChannelOrderInfo(
    val groupKey: String,
    val seq: Int,
    val groupOrder: Int,
    val serverOrder: Int
)

object ChannelNameNormalizer {

    // Matches leading bracketed tags like "[AR]", "(UK)", "{VIP}"
    private val LEADING_BRACKET_PREFIX_REGEX = Regex("""^\s*[\[(][a-z0-9\s_\-|:.]{1,10}[\])]\s*""")

    // Matches leading prefix before '|' or ':' (e.g., "AR:", "AR |", "VIP :", "EN|", "4K:")
    private val LEADING_COLON_PIPE_PREFIX_REGEX = Regex("""^\s*[a-z0-9]{1,6}\s*[|:]+\s*""")

    // Matches known country/language/tag prefixes before '-' (e.g., "AR - ", "EN - ", "UK - ", "VIP - ")
    private val LEADING_DASH_PREFIX_REGEX = Regex(
        """^\s*(?:ar|en|fr|de|tr|es|it|pt|ru|nl|us|uk|ca|au|be|ch|at|se|no|dk|fi|pl|gr|ro|bg|rs|hr|ba|al|ku|fa|ur|hi|in|pk|ir|iq|sy|lb|jo|ps|sa|ae|kw|qa|bh|om|ye|eg|ly|tn|dz|ma|sd|vip|hd|fhd|uhd|4k|8k|sd|hevc|raw|tv|live)\s*[-–—]+\s*"""
    )

    // Matches leading or trailing separator characters
    private val LEADING_SEPARATORS_REGEX = Regex("""^[\s|:\-–—_•*+#~><.,;!?/\\'"`]+""")
    private val TRAILING_SEPARATORS_REGEX = Regex("""[\s|:\-–—_•*+#~><.,;!?/\\'"`]+$""")

    // Matches a standalone number token (not attached to letters like 4k/1080p, or ratios/versions like 24/7, 5.1, h.265)
    private val STANDALONE_NUMBER_REGEX = Regex("""(?<![a-z0-9/._])(?:#(\d+)|\((\d+)\)|\[(\d+)\]|(\d+))(?![a-z0-9/._%])""")

    private val MULTI_SPACE_REGEX = Regex("""\s+""")

    /**
     * Computes (groupKey, seq) from a raw channel name:
     * - Lowercased
     * - Arabic-Indic digits (٠-٩ and ۰-۹) converted to Western digits (0-9)
     * - Leading symbols, emojis, and prefixes removed ("|", "-", ":", "AR:", "AR |", etc.)
     * - Extra spaces collapsed
     * - Last standalone number removed to form groupKey (keeping "4k", "1080p", etc. in groupKey)
     * - seq = that last standalone number as an Int (0 if the name has no standalone number)
     */
    fun normalize(rawName: String): NormalizedChannel {
        if (rawName.isBlank()) {
            return NormalizedChannel(groupKey = "", seq = 0, hasNumber = false)
        }

        // 1. Convert Arabic-Indic digits to Western digits & strip emojis/symbols
        val sb = StringBuilder(rawName.length)
        for (i in 0 until rawName.length) {
            val ch = rawName[i]
            when (ch) {
                in '\u0660'..'\u0669' -> sb.append(('0'.code + (ch.code - 0x0660)).toChar())
                in '\u06F0'..'\u06F9' -> sb.append(('0'.code + (ch.code - 0x06F0)).toChar())
                in '\uFE00'..'\uFE0F', '\u20E3', '\u200B', '\u200C', '\u200D', '\uFEFF' -> {
                    // Skip variation selectors and zero-width chars
                }
                else -> {
                    val type = Character.getType(ch).toByte()
                    if (type == Character.SURROGATE ||
                        type == Character.OTHER_SYMBOL ||
                        type == Character.PRIVATE_USE ||
                        type == Character.CONTROL ||
                        type == Character.FORMAT
                    ) {
                        sb.append(' ')
                    } else {
                        sb.append(ch.lowercaseChar())
                    }
                }
            }
        }

        var working = sb.toString().lowercase(Locale.ROOT)

        // 2. Repeatedly strip leading symbols and prefixes ("|", "-", ":", "[AR]", "AR:", "AR |", "AR -")
        var changed = true
        while (changed && working.isNotEmpty()) {
            changed = false

            val afterLeadingSep = LEADING_SEPARATORS_REGEX.replaceFirst(working, "")
            if (afterLeadingSep != working) {
                working = afterLeadingSep
                changed = true
            }

            val afterBracket = LEADING_BRACKET_PREFIX_REGEX.replaceFirst(working, "")
            if (afterBracket != working && afterBracket.isNotBlank()) {
                working = afterBracket
                changed = true
            }

            val afterColonPipe = LEADING_COLON_PIPE_PREFIX_REGEX.replaceFirst(working, "")
            if (afterColonPipe != working && afterColonPipe.isNotBlank()) {
                working = afterColonPipe
                changed = true
            }

            val afterDash = LEADING_DASH_PREFIX_REGEX.replaceFirst(working, "")
            if (afterDash != working && afterDash.isNotBlank()) {
                working = afterDash
                changed = true
            }
        }

        working = TRAILING_SEPARATORS_REGEX.replace(working, "")
        working = MULTI_SPACE_REGEX.replace(working, " ").trim()

        if (working.isEmpty()) {
            val fallback = MULTI_SPACE_REGEX.replace(rawName.lowercase(Locale.ROOT), " ").trim()
            return NormalizedChannel(groupKey = fallback, seq = 0, hasNumber = false)
        }

        // 3. Find the LAST standalone number in the cleaned name
        val matches = STANDALONE_NUMBER_REGEX.findAll(working).toList()
        if (matches.isEmpty()) {
            return NormalizedChannel(groupKey = working, seq = 0, hasNumber = false)
        }

        val lastMatch = matches.last()
        val digitsStr = lastMatch.groupValues.drop(1).firstOrNull { it.isNotEmpty() }.orEmpty()
        val parsedSeq = digitsStr.toIntOrNull() ?: 0

        val before = working.substring(0, lastMatch.range.first)
        val after = working.substring(lastMatch.range.last + 1)
        var baseName = "$before $after"
        baseName = LEADING_SEPARATORS_REGEX.replace(baseName, "")
        baseName = TRAILING_SEPARATORS_REGEX.replace(baseName, "")
        baseName = MULTI_SPACE_REGEX.replace(baseName, " ").trim()

        // If the channel name was solely a number (e.g. "1"), keep it as groupKey
        if (baseName.isEmpty()) {
            return NormalizedChannel(groupKey = working, seq = parsedSeq, hasNumber = true)
        }

        return NormalizedChannel(
            groupKey = baseName,
            seq = parsedSeq,
            hasNumber = true
        )
    }

    /**
     * Stateful tracker used while caching a list/stream of channels so that:
     * - groupOrder = position of the FIRST channel of that group in the server order
     * - Channels without a number stay alone in their own group (groupOrder = serverOrder)
     */
    class ChannelGroupingTracker(startServerOrder: Int = 0) {
        private var currentServerOrder = startServerOrder
        private val groupOrderMap = HashMap<String, Int>()

        fun nextChannel(rawName: String): ChannelOrderInfo {
            val serverOrder = currentServerOrder++
            val norm = normalize(rawName)
            val groupOrder = if (norm.hasNumber) {
                groupOrderMap.getOrPut(norm.groupKey) { serverOrder }
            } else {
                serverOrder
            }
            return ChannelOrderInfo(
                groupKey = norm.groupKey,
                seq = norm.seq,
                groupOrder = groupOrder,
                serverOrder = serverOrder
            )
        }
    }

    /**
     * Sorts an in-memory list of MediaItems (e.g. fallback or mixed lists) using the same rules as Room.
     */
    fun sortMediaItems(items: List<MediaItem>, order: LiveChannelOrder): List<MediaItem> {
        if (items.size <= 1) return items
        val tracker = ChannelGroupingTracker()
        val indexed = items.map { item ->
            val info = if (item.groupKey.isNotEmpty() || item.serverOrder > 0 || item.groupOrder > 0) {
                ChannelOrderInfo(
                    groupKey = item.groupKey.ifEmpty { normalize(item.name).groupKey },
                    seq = item.seq,
                    groupOrder = item.groupOrder,
                    serverOrder = item.serverOrder
                )
            } else {
                tracker.nextChannel(item.name)
            }
            Pair(item, info)
        }

        val sorted = when (order) {
            LiveChannelOrder.SMART -> indexed.sortedWith(
                compareBy<Pair<MediaItem, ChannelOrderInfo>> { it.second.groupOrder }
                    .thenBy { it.second.seq }
                    .thenBy { it.first.name }
            )
            LiveChannelOrder.SERVER -> indexed.sortedWith(
                compareBy<Pair<MediaItem, ChannelOrderInfo>> { it.second.serverOrder }
                    .thenBy { it.first.name }
            )
            LiveChannelOrder.A_TO_Z -> indexed.sortedWith(
                compareBy<Pair<MediaItem, ChannelOrderInfo>> { it.second.groupKey }
                    .thenBy { it.second.seq }
                    .thenBy { it.first.name }
            )
        }
        return sorted.map { it.first }
    }

    /**
     * Sorts FavoriteItems using the active LiveChannelOrder.
     */
    fun sortFavorites(items: List<FavoriteItem>, order: LiveChannelOrder): List<FavoriteItem> {
        if (items.size <= 1) return items
        val tracker = ChannelGroupingTracker()
        val indexed = items.map { fav ->
            val computed = tracker.nextChannel(fav.title)
            val info = if (fav.groupKey.isNotEmpty() && (fav.groupOrder > 0 || fav.serverOrder > 0)) {
                ChannelOrderInfo(
                    groupKey = fav.groupKey,
                    seq = fav.seq,
                    groupOrder = fav.groupOrder,
                    serverOrder = fav.serverOrder
                )
            } else {
                computed
            }
            Pair(fav, info)
        }

        val sorted = when (order) {
            LiveChannelOrder.SMART -> indexed.sortedWith(
                compareBy<Pair<FavoriteItem, ChannelOrderInfo>> { it.second.groupOrder }
                    .thenBy { it.second.seq }
                    .thenBy { it.first.title }
            )
            LiveChannelOrder.SERVER -> indexed.sortedWith(
                compareBy<Pair<FavoriteItem, ChannelOrderInfo>> { it.second.serverOrder }
                    .thenBy { it.first.title }
            )
            LiveChannelOrder.A_TO_Z -> indexed.sortedWith(
                compareBy<Pair<FavoriteItem, ChannelOrderInfo>> { it.second.groupKey }
                    .thenBy { it.second.seq }
                    .thenBy { it.first.title }
            )
        }
        return sorted.map { it.first }
    }
}
