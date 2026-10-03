package app.zornslemma.dayfile.ui

import app.zornslemma.dayfile.MainDispatcherRule
import app.zornslemma.dayfile.data.CategoryDao
import app.zornslemma.dayfile.data.CategoryEntity
import app.zornslemma.dayfile.data.CategoryRepository
import app.zornslemma.dayfile.data.EntryDao
import app.zornslemma.dayfile.data.EntryEntity
import app.zornslemma.dayfile.data.EntryStats
import app.zornslemma.dayfile.data.HistoryCategoryEntity
import app.zornslemma.dayfile.data.HistoryDao
import app.zornslemma.dayfile.data.HistoryEntryEntity
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

// JVM contract tests for the parts of CategoryEditViewModel the UI cannot observe: the
// deliberate asymmetry between drag-driven reorder (in-memory only, persisted once by
// commitOrder at gesture end) and discrete moveUp/moveDown (persisted immediately). Pinning
// both halves guards against regression in either direction: eager database writes per drag
// frame, or a broken screen-side commitOrder hookup silently losing the final order.
@OptIn(ExperimentalCoroutinesApi::class)
class CategoryEditViewModelTest {

    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    private lateinit var categoryDao: FakeCategoryDao
    private lateinit var fakeEntryDao: FakeEntryDao
    private lateinit var viewModel: CategoryEditViewModel

    private fun cat(id: Long, name: String, ordering: Int) =
        CategoryEntity(id = id, name = name, ordering = ordering, enabled = true)

    private fun seedWith(vararg categories: CategoryEntity) {
        categoryDao = FakeCategoryDao(categories.toList())
        fakeEntryDao = FakeEntryDao()
        viewModel =
            CategoryEditViewModel(
                categoryRepository = CategoryRepository(categoryDao, FakeHistoryDao()),
                entryDao = fakeEntryDao,
            )
        // Run the init collector so the seeded categories reach the ViewModel's state flow.
        mainDispatcherRule.testDispatcher.scheduler.advanceUntilIdle()
    }

    @Test
    fun `reorder defers persistence until commitOrder`() {
        seedWith(cat(1, "Alpha", 0), cat(2, "Beta", 1), cat(3, "Gamma", 2))

        viewModel.reorder(from = 0, to = 2)
        mainDispatcherRule.testDispatcher.scheduler.advanceUntilIdle()

        // The in-memory list must reflect the new arrangement immediately (this is what the
        // UI renders during the drag)...
        assertEquals(listOf(2L, 3L, 1L), viewModel.categories.value.map { it.id })
        // ...but nothing may have been written: reorder fires once per drag frame.
        assertTrue(
            "reorder wrote to the database before commitOrder",
            categoryDao.updateCalls.isEmpty(),
        )

        viewModel.commitOrder()
        mainDispatcherRule.testDispatcher.scheduler.advanceUntilIdle()

        // Exactly one write, containing consecutive orderings for the final arrangement.
        assertEquals(1, categoryDao.updateCalls.size)
        val written = categoryDao.updateCalls.single()
        assertEquals(listOf(2L, 3L, 1L), written.map { it.id })
        assertEquals(listOf(0, 1, 2), written.map { it.ordering })
    }

    @Test
    fun `moveUp on first row and moveDown on last row are complete no-ops`() {
        seedWith(cat(1, "Alpha", 0), cat(2, "Beta", 1), cat(3, "Gamma", 2))

        viewModel.moveUp(1L)
        viewModel.moveDown(3L)
        mainDispatcherRule.testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(1L, 2L, 3L), viewModel.categories.value.map { it.id })
        assertTrue("boundary move wrote to the database", categoryDao.updateCalls.isEmpty())
    }

    @Test
    fun `moveUp persists immediately with consecutive orderings`() {
        seedWith(cat(1, "Alpha", 0), cat(2, "Beta", 1), cat(3, "Gamma", 2))

        viewModel.moveUp(2L)
        mainDispatcherRule.testDispatcher.scheduler.advanceUntilIdle()

        // Discrete moves are single user actions, so unlike reorder they persist at once.
        assertEquals(listOf(2L, 1L, 3L), viewModel.categories.value.map { it.id })
        assertEquals(1, categoryDao.updateCalls.size)
        val written = categoryDao.updateCalls.single()
        assertEquals(listOf(2L, 1L, 3L), written.map { it.id })
        assertEquals(listOf(0, 1, 2), written.map { it.ordering })
    }

    @Test
    fun `newer openDeleteDialog supersedes an in-flight stats fetch`() {
        seedWith(cat(1, "Alpha", 0), cat(2, "Beta", 1))

        // Park Alpha's fetch mid-flight on its per-category gate; Beta's gate is uncompleted
        // too, but we never park on it - see below.
        viewModel.openDeleteDialog(cat(1, "Alpha", 0))
        mainDispatcherRule.testDispatcher.scheduler.runCurrent()
        assertTrue("Alpha's fetch should be parked on its gate", fakeEntryDao.isGated(1L))

        // A second request arrives while the first is still in flight. Its gate is released
        // up front so its fetch completes immediately.
        fakeEntryDao.releaseGate(2L)
        viewModel.openDeleteDialog(cat(2, "Beta", 1))
        mainDispatcherRule.testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("Beta", viewModel.deleteDialogState.value?.category?.name)

        // NOW release Alpha's long-stale fetch. The superseded job must have been cancelled:
        // if it were still alive it would land last and clobber the dialog with Alpha's stats.
        fakeEntryDao.releaseGate(1L)
        mainDispatcherRule.testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(
            "stale stats fetch clobbered the newer delete dialog state",
            "Beta",
            viewModel.deleteDialogState.value?.category?.name,
        )
    }

    // --- Fakes ---

    private class FakeCategoryDao(initial: List<CategoryEntity>) : CategoryDao {
        val categories = MutableStateFlow(initial)
        val updateCalls = mutableListOf<List<CategoryEntity>>()
        private var nextAutoId = (initial.maxOfOrNull { it.id } ?: 0L) + 1

        override suspend fun insertAll(categories: List<CategoryEntity>): List<Long> {
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

        // Room's query is "SELECT * FROM category ORDER BY ordering ASC, id ASC": the ORDER BY
        // lives in the SQL, not in the table. The fake must therefore expose a sorted VIEW;
        // returning the raw storage list makes a persisted reorder appear to revert, because
        // updateCategories below replaces entities positionally (an UPDATE leaves rows where
        // they are - only a fresh ordered READ re-sorts them).
        override fun observeAllCategories(): Flow<List<CategoryEntity>> =
            categories.map { list -> list.sortedWith(compareBy({ it.ordering }, { it.id })) }

        override fun observeAllEnabledCategories(): Flow<List<CategoryEntity>> =
            categories.map { list -> list.filter { it.enabled } }

        override suspend fun updateCategories(categories: List<CategoryEntity>) {
            updateCalls += categories
            val byId = categories.associateBy { it.id }
            this.categories.value = this.categories.value.map { byId[it.id] ?: it }
        }

        override suspend fun delete(category: CategoryEntity) {
            categories.value = categories.value.filterNot { it.id == category.id }
        }

        override suspend fun getAllCategories(): List<CategoryEntity> =
            categories.value.sortedWith(compareBy({ it.ordering }, { it.id }))

        override suspend fun getCategory(categoryId: Long): CategoryEntity? =
            categories.value.firstOrNull { it.id == categoryId }
    }

    private class FakeHistoryDao : HistoryDao {
        override suspend fun insertHistory(entry: HistoryEntryEntity) {}

        override fun observeHistoryForDate(date: LocalDate): Flow<List<HistoryEntryEntity>> =
            flowOf(emptyList())

        override fun observeHistoryForDateAndCategory(
            date: LocalDate,
            categoryId: Long,
        ): Flow<List<HistoryEntryEntity>> = flowOf(emptyList())

        override fun observeAllCategories(): Flow<List<HistoryCategoryEntity>> = flowOf(emptyList())

        override suspend fun pruneOld(cutoff: Long) {}

        override suspend fun clearAll() {}

        override suspend fun getHistoryCount(): Int = 0

        override suspend fun upsertCategory(name: HistoryCategoryEntity) {}
    }

    private class FakeEntryDao : EntryDao {
        // Per-category gates letting tests park getEntryStatsForCategory mid-flight, to prove
        // the openDeleteDialog supersession contract deterministically (no timing dependence).
        private val gates = mutableMapOf<Long, CompletableDeferred<Unit>>()

        private fun gateFor(categoryId: Long): CompletableDeferred<Unit> =
            gates.getOrPut(categoryId) { CompletableDeferred() }

        fun isGated(categoryId: Long): Boolean = !gateFor(categoryId).isCompleted

        fun releaseGate(categoryId: Long) {
            gateFor(categoryId).complete(Unit)
        }

        override suspend fun getEntryStatsForCategory(categoryId: Long): EntryStats {
            gateFor(categoryId).await()
            return EntryStats(count = 0, earliestDate = null, latestDate = null)
        }

        override suspend fun insert(entry: EntryEntity) {}

        override suspend fun update(entry: EntryEntity) {}

        override suspend fun delete(entry: EntryEntity) {}

        override suspend fun getEntryForCategoryAndDate(
            categoryId: Long,
            date: LocalDate,
        ): EntryEntity? = null

        override fun observeEntriesForDate(date: LocalDate): Flow<List<EntryEntity>> =
            flowOf(emptyList())

        override suspend fun getAllEntries(): List<EntryEntity> = emptyList()
    }
}
