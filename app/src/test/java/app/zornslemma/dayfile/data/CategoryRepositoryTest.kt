package app.zornslemma.dayfile.data

import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Tests the repository's cross-database coordination with recording fakes. The most important
// pin is the ordering inside deleteCategory: the category's name MUST be archived into the
// history database before the main-database row is deleted, because after deletion the history
// archive is the only surviving source of the name for displaying historical entries. Swapping
// those two lines would introduce a window where a crash loses the name permanently.
class CategoryRepositoryTest {

    private lateinit var categoryDao: FakeCategoryDao
    private lateinit var historyDao: FakeHistoryDao
    private lateinit var repository: CategoryRepository

    private fun createWith(initialCategories: List<CategoryEntity> = emptyList()) {
        val events = mutableListOf<String>()
        categoryDao = FakeCategoryDao(initialCategories, events)
        historyDao = FakeHistoryDao(events)
        repository = CategoryRepository(categoryDao, historyDao)
    }

    @Test
    fun `deleteCategory archives name before deleting`() = runBlocking {
        createWith()
        categoryDao.insert(CategoryEntity(id = 1, name = "Diet", ordering = 0, enabled = false))

        repository.deleteCategory(
            CategoryEntity(id = 1, name = "Diet", ordering = 0, enabled = false)
        )

        // Strict sequence: archive first, delete second. Anything else is a regression.
        assertEquals(listOf("archive:Diet", "delete:1"), categoryDao.events)
        assertTrue(categoryDao.categories.value.none { it.id == 1L })
        // The archive row survives in the history database.
        assertEquals("Diet", historyDao.upsertedCategories.single { it.id == 1L }.name)
    }

    // ensureDefaultCategoriesExist is covered by CategoryRepositoryDefaultCategoriesTest in
    // androidTest: it needs a real Context for the localized default names and real Room for
    // the insertAll ID round-trip, which the fakes here cannot provide.

    @Test
    fun `upsert new category assigns max ordering plus one and primes history`() = runBlocking {
        createWith()
        // Deliberately non-consecutive existing ordering: pins the max()+1 semantics rather
        // than a coincidental "last index plus one".
        categoryDao.insert(CategoryEntity(name = "Existing", ordering = 5, enabled = true))

        val newId =
            repository.upsertCategory(
                name = "Snacks",
                autoCorrect = false,
                capitalization = CapitalizationMode.SENTENCES,
                categoryId = null,
            )

        val inserted = categoryDao.getCategory(newId)
        assertEquals("Snacks", inserted?.name)
        assertEquals(6, inserted?.ordering)
        assertEquals(true, inserted?.enabled)
        assertEquals(false, inserted?.autoCorrect)
        assertEquals(CapitalizationMode.SENTENCES, inserted?.capitalization)
        assertEquals("Snacks", historyDao.upsertedCategories.single { it.id == newId }.name)
    }

    @Test
    fun `upsert existing category updates fields and syncs history name`() = runBlocking {
        createWith()
        categoryDao.insert(
            CategoryEntity(
                name = "Diet",
                ordering = 0,
                enabled = true,
                autoCorrect = true,
                capitalization = CapitalizationMode.NONE,
            )
        )

        val id =
            repository.upsertCategory(
                name = "Food",
                autoCorrect = false,
                capitalization = CapitalizationMode.WORDS,
                categoryId = 1L,
            )

        assertEquals(1L, id)
        val updated = categoryDao.getCategory(1L)
        assertEquals("Food", updated?.name)
        assertEquals(false, updated?.autoCorrect)
        assertEquals(CapitalizationMode.WORDS, updated?.capitalization)
        // Unrelated fields must survive the update untouched.
        assertEquals(0, updated?.ordering)
        assertEquals(true, updated?.enabled)
        assertEquals("Food", historyDao.upsertedCategories.single { it.id == 1L }.name)
    }

    // --- Fakes ---

    // Both fakes append human-readable events to one shared log so tests can assert the exact
    // cross-database call ORDER, not merely that each call happened.
    private class FakeCategoryDao(initial: List<CategoryEntity>, val events: MutableList<String>) :
        CategoryDao {
        val categories = MutableStateFlow(initial)
        val insertAllCalls = mutableListOf<List<CategoryEntity>>()
        private var nextAutoId = (initial.maxOfOrNull { it.id } ?: 0L) + 1

        override suspend fun insertAll(categories: List<CategoryEntity>): List<Long> {
            insertAllCalls += categories
            val ids = categories.map { nextAutoId++ }
            this.categories.value =
                this.categories.value + categories.mapIndexed { i, c -> c.copy(id = ids[i]) }
            return ids
        }

        override suspend fun insert(category: CategoryEntity): Long {
            val id = nextAutoId++
            categories.value = categories.value + category.copy(id = id)
            return id
        }

        override suspend fun getCount(): Int = categories.value.size

        // Sorted view mirroring Room's "ORDER BY ordering ASC, id ASC", matching the fake in
        // CategoryEditViewModelTest: returning raw storage order makes a persisted reorder
        // appear to revert on the next read. No current test observes through this method after
        // a rearranging write, but keeping the fakes consistent avoids re-learning that lesson.
        override fun observeAllCategories(): Flow<List<CategoryEntity>> =
            categories.map { list -> list.sortedWith(compareBy({ it.ordering }, { it.id })) }

        override fun observeAllEnabledCategories(): Flow<List<CategoryEntity>> =
            categories.map { list -> list.filter { it.enabled } }

        override suspend fun updateCategories(categories: List<CategoryEntity>) {
            val byId = categories.associateBy { it.id }
            this.categories.value = this.categories.value.map { byId[it.id] ?: it }
        }

        override suspend fun delete(category: CategoryEntity) {
            events += "delete:${category.id}"
            categories.value = categories.value.filterNot { it.id == category.id }
        }

        override suspend fun getAllCategories(): List<CategoryEntity> =
            categories.value.sortedWith(compareBy({ it.ordering }, { it.id }))

        override suspend fun getCategory(categoryId: Long): CategoryEntity? =
            categories.value.firstOrNull { it.id == categoryId }
    }

    private class FakeHistoryDao(val events: MutableList<String>) : HistoryDao {
        val upsertedCategories = mutableListOf<HistoryCategoryEntity>()

        override suspend fun insertHistory(entry: HistoryEntryEntity) {}

        override fun observeHistoryForDate(date: LocalDate): Flow<List<HistoryEntryEntity>> =
            flowOf(emptyList())

        override fun observeHistoryForDateAndCategory(
            date: LocalDate,
            categoryId: Long,
        ): Flow<List<HistoryEntryEntity>> = flowOf(emptyList())

        override fun observeAllCategories(): Flow<List<HistoryCategoryEntity>> =
            flowOf(upsertedCategories.toList())

        override suspend fun pruneOld(cutoff: Long) {}

        override suspend fun clearAll() {}

        override suspend fun getHistoryCount(): Int = 0

        override suspend fun upsertCategory(name: HistoryCategoryEntity) {
            events += "archive:${name.name}"
            upsertedCategories.removeAll { it.id == name.id }
            upsertedCategories += name
        }
    }
}
