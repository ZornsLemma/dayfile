package app.zornslemma.dayfile.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {
    @Insert suspend fun insertHistory(entry: HistoryEntryEntity)

    @Query("SELECT * FROM history_entry WHERE date = :date")
    fun observeHistoryForDate(date: LocalDate): Flow<List<HistoryEntryEntity>>

    @Query("SELECT * FROM history_entry WHERE date = :date AND category_id = :categoryId")
    fun observeHistoryForDateAndCategory(
        date: LocalDate,
        categoryId: Long,
    ): Flow<List<HistoryEntryEntity>>

    @Query("DELETE FROM history_entry WHERE saved_at < :cutoff") suspend fun pruneOld(cutoff: Long)

    @Query("DELETE FROM history_entry") suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM history_entry") suspend fun getHistoryCount(): Int

    @Upsert suspend fun upsertCategory(name: HistoryCategoryEntity)

    @Query("SELECT * FROM history_category")
    fun observeAllCategories(): Flow<List<HistoryCategoryEntity>>
}
