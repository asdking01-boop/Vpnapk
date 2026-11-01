package com.example.data.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.data.local.dao.MediaDao
import com.example.data.local.entity.CategoryEntity
import com.example.data.local.entity.MediaItemEntity

@Database(
    entities = [MediaItemEntity::class, CategoryEntity::class],
    version = 1,
    exportSchema = false
)
abstract class KrasDatabase : RoomDatabase() {

    abstract fun mediaDao(): MediaDao

    companion object {
        @Volatile
        private var INSTANCE: KrasDatabase? = null

        fun getInstance(context: Context): KrasDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    KrasDatabase::class.java,
                    "kras_tv_media.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
