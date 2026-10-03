package app.zornslemma.dayfile.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

// Single source of truth for the main database's schema version: consumed by the @Database
// annotation below and by BackupRestoreHelper's restore validation (which rejects backup files
// newer than this). Declared top-level rather than in the companion object so other files can
// reference it as a compile-time constant without qualifying through MainDatabase.
const val MAIN_DB_SCHEMA_VERSION = 1

@Database(
    entities = [CategoryEntity::class, EntryEntity::class],
    version = MAIN_DB_SCHEMA_VERSION,
    exportSchema = true,
)
@TypeConverters(DateTypeConverters::class)
abstract class MainDatabase : RoomDatabase() {
    abstract fun categoryDao(): CategoryDao

    abstract fun entryDao(): EntryDao

    companion object {
        // Must match the file name used by Room's databaseBuilder.
        const val DATABASE_NAME = "main.db"

        @Volatile private var INSTANCE: MainDatabase? = null

        fun getDatabase(context: Context): MainDatabase {
            return INSTANCE
                ?: synchronized(this) {
                    val instance =
                        Room.databaseBuilder(
                                context.applicationContext,
                                MainDatabase::class.java,
                                DATABASE_NAME,
                            )
                            .build()
                    INSTANCE = instance
                    instance
                }
        }

        /**
         * Clears the cached Room instance. Intended to be called after the database file has been
         * replaced on disk (e.g. during restore) so that the next call to [getDatabase] rebuilds
         * the connection against the new file.
         */
        fun resetInstanceForRestore() {
            INSTANCE = null
        }
    }
}
