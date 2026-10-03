package app.zornslemma.dayfile.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {
    @Insert suspend fun insertAll(categories: List<CategoryEntity>): List<Long>

    @Insert suspend fun insert(category: CategoryEntity): Long

    @Query("SELECT COUNT(*) FROM category") suspend fun getCount(): Int

    @Query("SELECT * FROM category ORDER BY ordering ASC, id ASC")
    fun observeAllCategories(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM category WHERE enabled = true")
    fun observeAllEnabledCategories(): Flow<List<CategoryEntity>>

    // Wrapped in a transaction so bulk updates are atomic.
    @Transaction @Update suspend fun updateCategories(categories: List<CategoryEntity>)

    @Delete suspend fun delete(category: CategoryEntity)

    @Query("SELECT * FROM category ORDER BY ordering ASC, id ASC")
    suspend fun getAllCategories(): List<CategoryEntity>

    @Query("SELECT * FROM category WHERE id = :categoryId")
    suspend fun getCategory(categoryId: Long): CategoryEntity?
}
