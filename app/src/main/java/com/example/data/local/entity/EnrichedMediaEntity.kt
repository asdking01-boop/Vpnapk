package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "enriched_media_cache",
    indices = [
        Index(value = ["updatedAtMs"]),
        Index(value = ["tmdbId"]),
        Index(value = ["streamId", "mediaType"])
    ]
)
data class EnrichedMediaEntity(
    @PrimaryKey
    val mediaKey: String, // e.g. "vod_123" or "series_456"
    val streamId: Int,
    val mediaType: String, // "MOVIE" or "SERIES"
    val tmdbId: Int,
    val jsonData: String,
    val updatedAtMs: Long = System.currentTimeMillis()
)
