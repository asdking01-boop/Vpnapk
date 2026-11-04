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
    version = 3,
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
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_items_quality ON media_items(quality)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_items_accountId_type_quality ON media_items(accountId, type, quality)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_items_accountId_type_categoryId_quality ON media_items(accountId, type, categoryId, quality)")
            }
        }

        fun getInstance(context: Context): KrasDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    KrasDatabase::class.java,
                    "kras_tv_media.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
