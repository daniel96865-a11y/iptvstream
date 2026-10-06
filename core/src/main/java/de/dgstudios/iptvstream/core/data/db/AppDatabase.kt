package de.dgstudios.iptvstream.core.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ProfileEntity::class,
        CategoryEntity::class,
        ChannelEntity::class,
        MovieEntity::class,
        SeriesEntity::class,
        EpgEntity::class,
        EpgNameEntity::class,
        FavoriteEntity::class,
        ProgressEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun profiles(): ProfileDao
    abstract fun content(): ContentDao
    abstract fun user(): UserDao
    abstract fun epg(): EpgDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "iptv.db")
                .addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigration()
                .build()

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE channels ADD COLUMN archiveDays INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE channels ADD COLUMN catchupMode TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE channels ADD COLUMN catchupSource TEXT NOT NULL DEFAULT ''")
            }
        }
    }
}
