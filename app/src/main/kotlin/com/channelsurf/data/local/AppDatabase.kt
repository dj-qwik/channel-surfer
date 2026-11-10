package com.channelsurf.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

@Database(
    entities = [
        ChannelEntity::class,
        MediaAssetEntity::class,
        PlaylistEntryEntity::class,
        ProgramBlockEntity::class,
        ManifestRevisionEntity::class,
        TakedownEntity::class,
        ArchiveCacheEntity::class,
        DismissedEntity::class,
    ],
    version = AppDatabase.VERSION,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun catalogDao(): CatalogDao
    abstract fun scheduleDao(): ScheduleDao
    abstract fun auditDao(): AuditDao
    abstract fun archiveCacheDao(): ArchiveCacheDao

    companion object {
        const val VERSION = 2
        const val NAME = "channel_surf.db"

        /**
         * Every schema change must bump [VERSION], add a Migration here, and commit the regenerated
         * app/schemas/<version>.json. Destructive fallback is deliberately NOT enabled: the catalog holds
         * operator-curated rights decisions and takedowns that must never be silently wiped.
         */
        val MIGRATIONS: Array<Migration> = arrayOf(
            object : Migration(1, 2) {
                override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE channels ADD COLUMN fallbackTags TEXT NOT NULL DEFAULT ''")
                    db.execSQL("ALTER TABLE channels ADD COLUMN ignoreEra INTEGER NOT NULL DEFAULT 0")
                }
            },
        )

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME)
                .addMigrations(*MIGRATIONS)
                .build()

        fun inMemory(context: Context): AppDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }
}
