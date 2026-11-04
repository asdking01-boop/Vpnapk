package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.data.local.ChannelNameNormalizer
import com.example.data.local.QualityDetector
import com.example.data.model.MediaItem
import com.example.data.model.MediaType

@Entity(
    tableName = "media_items",
    indices = [
        Index(value = ["accountId"]),
        Index(value = ["type"]),
        Index(value = ["categoryId"]),
        Index(value = ["addedTimestamp"]),
        Index(value = ["rating"]),
        Index(value = ["year"]),
        Index(value = ["quality"]),
        Index(value = ["accountId", "type"]),
        Index(value = ["accountId", "type", "categoryId"]),
        Index(value = ["accountId", "type", "quality"]),
        Index(value = ["accountId", "type", "categoryId", "quality"]),
        Index(value = ["groupKey"]),
        Index(value = ["groupOrder", "seq", "name"]),
        Index(value = ["accountId", "categoryId", "groupOrder", "seq"]),
        Index(value = ["accountId", "type", "groupOrder", "seq", "name"]),
        Index(value = ["accountId", "type", "categoryId", "groupOrder", "seq", "name"]),
        Index(value = ["accountId", "type", "groupKey", "seq", "name"]),
        Index(value = ["accountId", "type", "categoryId", "groupKey", "seq", "name"]),
        Index(value = ["accountId", "type", "serverOrder", "name"]),
        Index(value = ["accountId", "type", "categoryId", "serverOrder", "name"])
    ]
)
data class MediaItemEntity(
    @PrimaryKey
    val id: String,
    val accountId: String,
    val streamId: Int,
    val name: String,
    val title: String,
    val type: String, // "LIVE", "MOVIE", "SERIES"
    val posterUrl: String,
    val backdropUrl: String,
    val categoryId: String,
    val categoryName: String,
    val rating: Double,
    val year: String,
    val resolution: String,
    val quality: String,
    val streamUrl: String,
    val containerExtension: String,
    val addedTimestamp: Long,
    val groupKey: String = ChannelNameNormalizer.normalize(name).groupKey,
    val seq: Int = ChannelNameNormalizer.normalize(name).seq,
    val groupOrder: Int = 0,
    val serverOrder: Int = 0
) {
    fun toMediaItem(): MediaItem {
        val canonicalQuality = quality.ifBlank { resolution }
        return MediaItem(
            id = id,
            streamId = streamId,
            name = name,
            title = title.ifBlank { name },
            type = try { MediaType.valueOf(type) } catch (e: Exception) { MediaType.MOVIE },
            streamIcon = posterUrl,
            backdrop = backdropUrl.ifBlank { posterUrl },
            categoryId = categoryId,
            categoryName = categoryName,
            rating = rating,
            year = year,
            resolution = canonicalQuality,
            quality = canonicalQuality,
            streamUrl = streamUrl,
            containerExtension = containerExtension,
            addedTimestamp = addedTimestamp,
            groupKey = groupKey,
            seq = seq,
            groupOrder = groupOrder,
            serverOrder = serverOrder
        )
    }

    companion object {
        fun computeCanonicalQuality(
            name: String,
            categoryName: String,
            explicitQuality: String = "",
            explicitResolution: String = ""
        ): String {
            val hint = explicitQuality.ifBlank { explicitResolution }.trim()
            if (hint.isNotEmpty()) {
                val parsed = QualityDetector.parseStoredQuality(hint)
                if (parsed.badge.isNotEmpty() || parsed.extraTags.isNotEmpty()) {
                    val nameExtras = QualityDetector.detectExtraTagsFromText("$name $categoryName")
                    val mergedExtras = (parsed.extraTags + nameExtras).distinct()
                    val badge = parsed.badge.ifEmpty {
                        QualityDetector.parseStoredQuality(
                            QualityDetector.detectFromNameAndCategory(name, categoryName)
                        ).badge
                    }
                    return QualityDetector.ParsedQuality(badge, mergedExtras).toStorageString()
                }
            }
            return QualityDetector.detectFromNameAndCategory(name, categoryName, hint)
        }

        fun fromMediaItem(
            item: MediaItem,
            accountId: String,
            groupOrderOverride: Int? = null,
            serverOrderOverride: Int? = null
        ): MediaItemEntity {
            val norm = ChannelNameNormalizer.normalize(item.name)
            val detectedQuality = computeCanonicalQuality(
                name = item.name,
                categoryName = item.categoryName,
                explicitQuality = item.quality,
                explicitResolution = item.resolution
            )
            return MediaItemEntity(
                id = if (item.id.isNotBlank()) item.id else "${item.type.name.lowercase()}_${item.streamId}",
                accountId = accountId,
                streamId = item.streamId,
                name = item.name,
                title = item.title.ifBlank { item.name },
                type = item.type.name,
                posterUrl = item.streamIcon,
                backdropUrl = item.backdrop,
                categoryId = item.categoryId,
                categoryName = item.categoryName,
                rating = item.rating,
                year = item.year,
                resolution = detectedQuality,
                quality = detectedQuality,
                streamUrl = item.streamUrl,
                containerExtension = item.containerExtension,
                addedTimestamp = if (item.addedTimestamp > 0) item.addedTimestamp else System.currentTimeMillis(),
                groupKey = item.groupKey.ifBlank { norm.groupKey },
                seq = if (item.groupKey.isNotBlank()) item.seq else norm.seq,
                groupOrder = groupOrderOverride ?: item.groupOrder,
                serverOrder = serverOrderOverride ?: item.serverOrder
            )
        }

        fun fromMediaItemList(items: List<MediaItem>, accountId: String): List<MediaItemEntity> {
            val tracker = ChannelNameNormalizer.ChannelGroupingTracker()
            return items.map { item ->
                val info = tracker.nextChannel(item.name)
                val detectedQuality = computeCanonicalQuality(
                    name = item.name,
                    categoryName = item.categoryName,
                    explicitQuality = item.quality,
                    explicitResolution = item.resolution
                )
                MediaItemEntity(
                    id = if (item.id.isNotBlank()) item.id else "${item.type.name.lowercase()}_${item.streamId}",
                    accountId = accountId,
                    streamId = item.streamId,
                    name = item.name,
                    title = item.title.ifBlank { item.name },
                    type = item.type.name,
                    posterUrl = item.streamIcon,
                    backdropUrl = item.backdrop,
                    categoryId = item.categoryId,
                    categoryName = item.categoryName,
                    rating = item.rating,
                    year = item.year,
                    resolution = detectedQuality,
                    quality = detectedQuality,
                    streamUrl = item.streamUrl,
                    containerExtension = item.containerExtension,
                    addedTimestamp = if (item.addedTimestamp > 0) item.addedTimestamp else System.currentTimeMillis(),
                    groupKey = info.groupKey,
                    seq = info.seq,
                    groupOrder = info.groupOrder,
                    serverOrder = info.serverOrder
                )
            }
        }
    }
}
