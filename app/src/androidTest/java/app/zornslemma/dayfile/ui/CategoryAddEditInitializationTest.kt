package app.zornslemma.dayfile.ui

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.zornslemma.dayfile.R
import app.zornslemma.dayfile.data.CapitalizationMode
import app.zornslemma.dayfile.data.CategoryDao
import app.zornslemma.dayfile.data.CategoryEntity
import app.zornslemma.dayfile.data.CategoryRepository
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the process-death-style initialization case: navigation can restore the edit form before
 * the newly created CategoryEditViewModel has received the database's first category list.
 *
 * The normal UI route waits for the category list before the user can open the form. These tests
 * deliberately compose the edit destination first and publish its category afterwards, preventing a
 * form from being constructed from the ViewModel's temporary empty-list defaults.
 *
 * ## Why a fake DAO rather than the real in-memory Room database
 *
 * The behaviour under test is the ordering *before* the flow's first emission, so the precondition
 * has to be "the flow has not emitted yet". Real Room always emits its current content promptly on
 * subscription, and there is no supported way to hold that first emission back from outside, so a
 * Room-backed version could not create the state being tested - it would only ever exercise the
 * already-loaded path that the normal route takes. [DelayedCategoryDao] makes the otherwise
 * timing-dependent window deterministic.
 *
 * The fake therefore owes us fidelity on the contracts the production code relies on. In particular
 * it reproduces `ORDER BY ordering ASC, id ASC` from the real
 * `observeAllCategories`/`getAllCategories` queries: omitting it would silently reorder categories
 * and could make correct production code look like it had regressed. The unguarded
 * `observeAllEnabledCategories` query genuinely has no ORDER BY, and the fake matches that too.
 */
class CategoryAddEditInitializationTest : BaseAppTest() {

    @Test
    fun editFormWaitsForCategoryAndSeedsAllInitialValues() {
        val category =
            CategoryEntity(
                id = 7L,
                name = "Tea",
                ordering = 3,
                enabled = true,
                autoCorrect = false,
                capitalization = CapitalizationMode.WORDS,
            )
        val delayedCategories = DelayedCategoryDao()
        val viewModel = buildViewModel(delayedCategories)
        val backCalls = AtomicInteger()

        composeTestRule.setContent {
            CategoryAddEditScreen(
                viewModel = viewModel,
                categoryId = category.id,
                onBack = { backCalls.incrementAndGet() },
            )
        }

        // Composed while the list is still empty, so the form must not have been built at all -
        // the top-bar title only exists once the form is composed.
        assertNoEditForm()

        delayedCategories.publish(listOf(category))
        awaitEditForm()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Tea").assertExists()
        composeTestRule
            .onNodeWithText(context.getString(R.string.capitalization_words))
            .assertExists()
        composeTestRule.onNodeWithTag("category_autocorrect_switch").assertIsOff()

        // Saving without user edits must preserve the complete loaded snapshot, rather than
        // replacing it with the placeholders a prematurely built form would have captured.
        composeTestRule.onNodeWithText(context.getString(R.string.save)).performClick()
        composeTestRule.waitForIdle()
        runBlocking { assertEquals(listOf(category), delayedCategories.getAllCategories()) }
        assertEquals(1, backCalls.get())
    }

    @Test
    fun loadedEditRouteWithMissingCategoryReturnsToList() {
        val delayedCategories = DelayedCategoryDao()
        val viewModel = buildViewModel(delayedCategories)
        val backCalls = AtomicInteger()

        composeTestRule.setContent {
            CategoryAddEditScreen(
                viewModel = viewModel,
                categoryId = 99L,
                onBack = { backCalls.incrementAndGet() },
            )
        }

        // The list loads, but without the category the route asked for. Treating that as an add
        // form would let Save create a new category instead of editing the requested one.
        delayedCategories.publish(emptyList())
        composeTestRule.waitUntil(timeoutMillis = 5_000) { backCalls.get() == 1 }
        composeTestRule.waitForIdle()

        assertNoEditForm()
        composeTestRule.onNodeWithText(context.getString(R.string.save)).assertDoesNotExist()
    }

    private fun assertNoEditForm() {
        composeTestRule
            .onNodeWithText(context.getString(R.string.edit_category_title))
            .assertDoesNotExist()
    }

    // Waits on what the test actually cares about - the form appearing - rather than on the
    // ViewModel flag that gates it, so the test survives a refactor of the gating mechanism.
    private fun awaitEditForm() {
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule
                .onAllNodesWithText(context.getString(R.string.edit_category_title))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    private fun buildViewModel(categoryDao: CategoryDao): CategoryEditViewModel =
        CategoryEditViewModel(
                categoryRepository = CategoryRepository(categoryDao, historyDao),
                entryDao = entryDao,
            )
            .also { trackViewModel(it) }

    /**
     * A minimal, fully functional CategoryDao whose list starts with no emission, so a subscriber
     * sees nothing until [publish] is called.
     */
    private class DelayedCategoryDao : CategoryDao {
        private val categoriesFlow = MutableStateFlow<List<CategoryEntity>?>(null)

        // Matches `ORDER BY ordering ASC, id ASC` on the real queries. Applied on read rather than
        // on write, so it stays correct however the list was assembled.
        private fun sorted(categories: List<CategoryEntity>): List<CategoryEntity> =
            categories.sortedWith(compareBy({ it.ordering }, { it.id }))

        private fun currentCategories(): List<CategoryEntity> =
            sorted(categoriesFlow.value.orEmpty())

        fun publish(categories: List<CategoryEntity>) {
            categoriesFlow.value = categories
        }

        override suspend fun insertAll(categories: List<CategoryEntity>): List<Long> {
            val firstId = (currentCategories().maxOfOrNull { it.id } ?: 0L) + 1L
            val inserted =
                categories.mapIndexed { index, category -> category.copy(id = firstId + index) }
            publish(currentCategories() + inserted)
            return inserted.map { it.id }
        }

        override suspend fun insert(category: CategoryEntity): Long {
            val id = (currentCategories().maxOfOrNull { it.id } ?: 0L) + 1L
            publish(currentCategories() + category.copy(id = id))
            return id
        }

        override suspend fun getCount(): Int = currentCategories().size

        override fun observeAllCategories(): Flow<List<CategoryEntity>> =
            categoriesFlow.filterNotNull().map { sorted(it) }

        // No ORDER BY on the real query, so none here either.
        override fun observeAllEnabledCategories(): Flow<List<CategoryEntity>> =
            observeAllCategories().map { categories -> categories.filter { it.enabled } }

        override suspend fun updateCategories(categories: List<CategoryEntity>) {
            val updatedById = categories.associateBy { it.id }
            publish(currentCategories().map { category -> updatedById[category.id] ?: category })
        }

        override suspend fun delete(category: CategoryEntity) {
            publish(currentCategories().filterNot { it.id == category.id })
        }

        override suspend fun getAllCategories(): List<CategoryEntity> = currentCategories()

        override suspend fun getCategory(categoryId: Long): CategoryEntity? =
            currentCategories().firstOrNull { it.id == categoryId }
    }
}
