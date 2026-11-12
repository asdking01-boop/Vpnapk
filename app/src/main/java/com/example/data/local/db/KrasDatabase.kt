package com.example.data.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.local.dao.MediaDao
import com.example.data.local.entity.CategoryEntity
import com.example.data.local.entity.MediaItemEntity

@Database(
    entities = [MediaItemEntity::class, CategoryEntity::class],
    version = 4,
    exportSchema = false
)
abstract class KrasDatabase : RoomDatabase() {

    abstract fun mediaDao(): MediaDao

    companion object {
        @Volatile
        private var INSTANCE: KrasDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media_items ADD COLUMN groupKey TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE media_items ADD COLUMN seq INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE media_items ADD COLUMN groupOrder INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE media_items ADD COLUMN serverOrder INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_items_groupKey ON media_items(groupKey)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_items_groupOrder_seq_name ON media_items(groupOrder, seq, name)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_items_accountId_categoryId_groupOrder_seq ON media_items(accountId, categoryId, groupOrder, seq)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_items_accountId_type_groupOrder_seq_name ON media_items(accountId, type, groupOrder, seq, name)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_items_accountId_type_categoryId_groupOrder_seq_name ON media_items(accountId, type, categoryId, groupOrder, seq, name)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_items_accountId_type_groupKey_seq_name ON media_items(accountId, type, groupKey, seq, name)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_items_accountId_type_categoryId_groupKey_seq_name ON media_items(accountId, type, categoryId, groupKey, seq, name)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_items_accountId_type_serverOrder_name ON media_items(accountId, type, serverOrder, name)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_items_accountId_type_categoryId_serverOrder_name ON media_items(accountId, type, categoryId, serverOrder, name)")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // In v1/v2, quality index or composite quality indices might be missing.
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_items_quality ON media_items(quality)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_items_accountId_type_quality ON media_items(accountId, type, quality)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_items_accountId_type_categoryId_quality ON media_items(accountId, type, categoryId, quality)")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add TMDb metadata and plot fields to media_items
                db.execSQL("ALTER TABLE media_items ADD COLUMN plot TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE media_items ADD COLUMN genre TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE media_items ADD COLUMN cast TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE media_items ADD COLUMN director TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE media_items ADD COLUMN tmdbId INTEGER NOT NULL DEFAULT 0")

                // Ensure all expected indices on media_items and categories exist
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_items_accountId ON media_items(accountId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_items_type ON media_items(type)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_items_categoryId ON media_items(categoryId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_items_addedTimestamp ON media_items(addedTimestamp)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_items_rating ON media_items(rating)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_items_year ON media_items(year)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_items_quality ON media_items(quality)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_items_accountId_type ON media_items(accountId, type)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_items_accountId_type_categoryId ON media_items(accountId, type, categoryId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_items_accountId_type_quality ON media_items(accountId, type, quality)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_items_accountId_type_categoryId_quality ON media_items(accountId, type, categoryId, quality)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_items_groupKey ON media_items(groupKey)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_items_groupOrder_seq_name ON media_items(groupOrder, seq, name)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_items_accountId_categoryId_groupOrder_seq ON media_items(accountId, categoryId, groupOrder, seq)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_items_accountId_type_groupOrder_seq_name ON media_items(accountId, type, groupOrder, seq, name)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_items_accountId_type_categoryId_groupOrder_seq_name ON media_items(accountId, type, categoryId, groupOrder, seq, name)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_items_accountId_type_groupKey_seq_name ON media_items(accountId, type, groupKey, seq, name)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_items_accountId_type_categoryId_groupKey_seq_name ON media_items(accountId, type, categoryId, groupKey, seq, name)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_items_accountId_type_serverOrder_name ON media_items(accountId, type, serverOrder, name)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_items_accountId_type_categoryId_serverOrder_name ON media_items(accountId, type, categoryId, serverOrder, name)")

                db.execSQL("CREATE INDEX IF NOT EXISTS index_categories_accountId ON categories(accountId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_categories_type ON categories(type)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_categories_accountId_type ON categories(accountId, type)")
            }
        }

        // Direct migration from v1 to v4 for users skipping intermediate versions
        val MIGRATION_1_4 = object : Migration(1, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_1_2.migrate(db)
                MIGRATION_2_3.migrate(db)
                MIGRATION_3_4.migrate(db)
            }
        }

        // Direct migration from v2 to v4
        val MIGRATION_2_4 = object : Migration(2, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_2_3.migrate(db)
                MIGRATION_3_4.migrate(db)
            }
        }

        fun getInstance(context: Context): KrasDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    KrasDatabase::class.java,
                    "kras_tv_media.db"
                )
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_1_4,
                        MIGRATION_2_4
                    )
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
