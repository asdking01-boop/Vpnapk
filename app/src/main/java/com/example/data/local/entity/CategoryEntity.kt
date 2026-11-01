package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.data.model.Category
import com.example.data.model.MediaType

@Entity(
    tableName = "categories",
    indices = [
        Index(value = ["accountId"]),
        Index(value = ["type"]),
        Index(value = ["accountId", "type"])
    ]
)
data class CategoryEntity(
    @PrimaryKey
    val id: String, // "${accountId}_${type}_${categoryId}"
    val accountId: String,
    val categoryId: String,
    val categoryName: String,
    val parentId: Int,
    val type: String // "LIVE", "MOVIE", "SERIES"
) {
    fun toCategory(): Category = Category(
        categoryId = categoryId,
        categoryName = categoryName,
        parentId = parentId,
        type = try { MediaType.valueOf(type) } catch (e: Exception) { MediaType.MOVIE }
    )

    companion object {
        fun fromCategory(category: Category, accountId: String): CategoryEntity = CategoryEntity(
            id = "${accountId}_${category.type.name}_${category.categoryId}",
            accountId = accountId,
            categoryId = category.categoryId,
            categoryName = category.categoryName,
            parentId = category.parentId,
            type = category.type.name
        )
    }
}
