package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
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
        Index(value = ["accountId", "type"]),
        Index(value = ["accountId", "type", "categoryId"])
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
    val addedTimestamp: Long
) {
    fun toMediaItem(): MediaItem = MediaItem(
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
        resolution = resolution,
        quality = quality,
        streamUrl = streamUrl,
        containerExtension = containerExtension,
        addedTimestamp = addedTimestamp
    )

    companion object {
        fun fromMediaItem(item: MediaItem, accountId: String): MediaItemEntity = MediaItemEntity(
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
            resolution = item.resolution,
            quality = item.quality,
            streamUrl = item.streamUrl,
            containerExtension = item.containerExtension,
            addedTimestamp = if (item.addedTimestamp > 0) item.addedTimestamp else System.currentTimeMillis()
        )
    }
}
