package app.zornslemma.dayfile.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

@Dao
interface EntryDao {
    @Insert suspend fun insert(entry: EntryEntity)

    @Update suspend fun update(entry: EntryEntity)

    @Delete suspend fun delete(entry: EntryEntity)

    @Query("SELECT * FROM entry WHERE category_id = :categoryId AND date = :date LIMIT 1")
    suspend fun getEntryForCategoryAndDate(categoryId: Long, date: LocalDate): EntryEntity?

    @Query("SELECT * FROM entry WHERE date = :date")
    fun observeEntriesForDate(date: LocalDate): Flow<List<EntryEntity>>

    @Query(
        "SELECT COUNT(*) AS count, MIN(date) AS earliestDate, MAX(date) AS latestDate FROM entry WHERE category_id = :categoryId"
    )
    suspend fun getEntryStatsForCategory(categoryId: Long): EntryStats

    @Query("SELECT * FROM entry") suspend fun getAllEntries(): List<EntryEntity>
}

data class EntryStats(val count: Int, val earliestDate: LocalDate?, val latestDate: LocalDate?)
