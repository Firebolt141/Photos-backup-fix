package com.firebolt141.ubertrag.data

import android.content.Context
import androidx.room.*

class Converters {
    @TypeConverter fun statusFromString(s: String): CopyStatus = CopyStatus.valueOf(s)
    @TypeConverter fun statusToString(s: CopyStatus): String = s.name
}

@Database(entities = [QueueItem::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun queueDao(): QueueDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "ubertrag.db"
            ).build().also { INSTANCE = it }
        }
    }
}
