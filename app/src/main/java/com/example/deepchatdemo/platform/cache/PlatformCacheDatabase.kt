package com.example.deepchatdemo.platform.cache

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        CachedProductEntity::class,
        CachedBindingEntity::class,
        CachedTagEntity::class,
        CacheMetadataEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class PlatformCacheDatabase : RoomDatabase() {
    abstract fun cacheDao(): PlatformCacheDao

    companion object {
        const val DATABASE_NAME = "hightac-platform-read-cache.db"

        fun create(context: Context): PlatformCacheDatabase = Room.databaseBuilder(
            context.applicationContext,
            PlatformCacheDatabase::class.java,
            DATABASE_NAME
        ).build()
    }
}
