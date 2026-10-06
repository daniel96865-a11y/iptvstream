package de.dgstudios.iptvstream.core.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

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
    version = 1,
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
                .fallbackToDestructiveMigration()
                .build()
    }
}
