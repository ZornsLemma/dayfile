package app.zornslemma.dayfile.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [HistoryEntryEntity::class, HistoryCategoryEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(DateTypeConverters::class)
abstract class HistoryDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao

    companion object {
        const val DATABASE_NAME = "history.db"

        @Volatile private var INSTANCE: HistoryDatabase? = null

        fun getDatabase(context: Context): HistoryDatabase {
            return INSTANCE
                ?: synchronized(this) {
                    val instance =
                        Room.databaseBuilder(
                                context.applicationContext,
                                HistoryDatabase::class.java,
                                DATABASE_NAME,
                            )
                            .build()
                    INSTANCE = instance
                    instance
                }
        }
    }
}
