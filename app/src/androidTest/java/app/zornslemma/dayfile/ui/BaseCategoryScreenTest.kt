package app.zornslemma.dayfile.ui

import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import app.zornslemma.dayfile.R
import app.zornslemma.dayfile.data.CapitalizationMode
import app.zornslemma.dayfile.data.CategoryRepository
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

// Category-screen-specific fixtures and interaction helpers, layered on the shared in-memory
// database setup from BaseAppTest. Mirrors the relationship between the home/history suites and
// their base classes: broad journey coverage lives in a smoke test class, focused
// single-behaviour checks in a basics class, and anything both need lives here.
abstract class BaseCategoryScreenTest : BaseAppTest() {

    protected fun buildCategoryEditViewModel(): CategoryEditViewModel =
        CategoryEditViewModel(
                categoryRepository = CategoryRepository(categoryDao, historyDao),
                entryDao = entryDao,
            )
            .also { trackViewModel(it) }

    // Navigation callbacks default to no-ops; tests exercising the wiring pass their own
    // recording lambdas, matching the home screen suite's convention.
    protected fun launchCategoryScreen(
        viewModel: CategoryEditViewModel = buildCategoryEditViewModel(),
        onBack: () -> Unit = {},
        onAddCategory: () -> Unit = {},
        onEditCategory: (Long) -> Unit = {},
    ) {
        composeTestRule.setContent {
            CategoryScreen(
                viewModel = viewModel,
                onBack = onBack,
                onAddCategory = onAddCategory,
                onEditCategory = onEditCategory,
            )
        }
        composeTestRule.waitForIdle()
    }

    // Launches the add (categoryId == null) or edit form. MUST be preceded by seeding at least
    // one category: the form captures its initial values at FIRST composition from the
    // collected flow (remember { TextFieldState(initialName) }), and a freshly built ViewModel
    // starts empty until the init collector receives Room's first emission. In production this
    // race cannot happen - the shared ViewModel is scoped to the "categories" destination and
    // is fully loaded before the form ever composes (see MainActivity) - so here we close it
    // explicitly by waiting for that first emission.
    protected fun launchCategoryAddEditScreen(
        viewModel: CategoryEditViewModel = buildCategoryEditViewModel(),
        categoryId: Long?,
        onBack: () -> Unit = {},
    ) {
        composeTestRule.waitUntil(timeoutMillis = 5_000) { viewModel.categories.value.isNotEmpty() }
        composeTestRule.setContent {
            CategoryAddEditScreen(viewModel = viewModel, categoryId = categoryId, onBack = onBack)
        }
        composeTestRule.waitForIdle()
    }

    protected fun openItemMenu(categoryId: Long) {
        composeTestRule.onNodeWithTag("category_menu_button_$categoryId").performClick()
        composeTestRule.waitForIdle()
    }

    // Selecting a menu item closes the dropdown automatically, so the menu must be reopened
    // (via openItemMenu) before every further action - same pattern as the home overflow menu.
    protected fun clickMenuItem(@StringRes resId: Int) {
        composeTestRule.onNodeWithText(context.getString(resId)).performClick()
        composeTestRule.waitForIdle()
    }

    // The helpers below that cause a database write are deliberately self-synchronising, in the
    // same spirit as BaseHomeScreenTest.type() waiting for a field to become editable. See
    // BaseAppTest.clickAndAwaitChange for why that matters and what it guarantees. Expectations are
    // read from the database immediately before the click rather than passed in, so no caller has
    // to restate what it already arranged.

    protected fun toggleEnabled(categoryId: Long) {
        clickAndAwaitChange(
            read = { runBlocking { categoryDao.getCategory(categoryId)!!.enabled } },
            click = { composeTestRule.onNodeWithTag("category_switch_$categoryId").performClick() },
        )
    }

    // Deliberately not clickAndAwaitChange: a delete is known to remove exactly one row, so
    // waiting for the specific count is a stronger claim than "the count changed".
    protected fun confirmDeleteDialog() {
        val countBefore = runBlocking { categoryDao.getCount() }
        composeTestRule.onNodeWithText(context.getString(R.string.delete)).performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking { categoryDao.getCount() == countBefore - 1 }
        }
        composeTestRule.waitForIdle()
    }

    protected fun cancelDeleteDialog() {
        composeTestRule.onNodeWithText(context.getString(android.R.string.cancel)).performClick()
        composeTestRule.waitForIdle()
    }

    // --- Add/edit form helpers ---

    protected fun typeCategoryName(text: String) {
        composeTestRule.onNodeWithTag("category_name_field").performTextInput(text)
        composeTestRule.waitForIdle()
    }

    protected fun replaceCategoryName(text: String) {
        composeTestRule.onNodeWithTag("category_name_field").performTextClearance()
        typeCategoryName(text)
    }

    protected fun clickSave() {
        composeTestRule.onNodeWithText(context.getString(R.string.save)).performClick()
        composeTestRule.waitForIdle()
    }

    // The top-left close icon, which routes through the dirty-check (unlike a plain back).
    protected fun clickClose() {
        composeTestRule
            .onNodeWithContentDescription(context.getString(R.string.close_content_description))
            .performClick()
        composeTestRule.waitForIdle()
    }

    protected fun toggleAutocorrect() {
        composeTestRule.onNodeWithTag("category_autocorrect_switch").performClick()
        composeTestRule.waitForIdle()
    }

    protected fun selectCapitalizationOption(@StringRes labelRes: Int) {
        composeTestRule.onNodeWithTag("category_capitalization_field").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(context.getString(labelRes)).performClick()
        composeTestRule.waitForIdle()
    }

    // Asserts the name field DISPLAYS [value]. Deliberately reads the EditableText semantics
    // rather than using assertTextEquals: a Material3 TextField merges its label ("Category
    // name") into the node's Text semantics alongside the displayed value, so the node's
    // combined text content can never equal just the value (same technique as
    // BaseHistoryScreenTest.assertFilterFieldShows).
    protected fun assertNameFieldShows(value: String) {
        val actual =
            composeTestRule
                .onNodeWithTag("category_name_field")
                .fetchSemanticsNode()
                .config[SemanticsProperties.EditableText]
        assertEquals(value, actual.toString())
    }

    // Saves travel via viewModelScope onto Room's own executors, so they can land slightly
    // after waitForIdle() quiesces the main thread. These poll on a specific observable fact
    // first; the caller then performs the full-table assertion with proper failure diffs.
    protected fun waitUntilCategoryCount(count: Int) {
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking { categoryDao.getCount() == count }
        }
    }

    // For reordering, which writes through the repository on Room's executor. The screen updates
    // its own StateFlow synchronously, so a positional UI assertion usually looks fine - but a
    // caller that then reads the table, or that starts the NEXT reorder, is racing the write.
    // Polls for the table itself, matching the wait-then-assert shape used elsewhere here.
    protected fun waitUntilCategoryOrder(expected: List<CategoryRow>) {
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking {
                categoryDao.getAllCategories().map {
                    CategoryRow(
                        it.id,
                        it.name,
                        it.ordering,
                        it.enabled,
                        it.autoCorrect,
                        it.capitalization,
                    )
                } == expected
            }
        }
    }

    protected fun waitUntilCategoryNamed(name: String) {
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking { categoryDao.getAllCategories().any { it.name == name } }
        }
    }

    protected fun waitUntilHistoryArchiveContains(name: String) {
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking { historyDao.observeAllCategories().first().any { it.name == name } }
        }
    }

    // --- Order/table assertion helpers ---

    // Vertical position of a text node, for pinning visual order. Only ever compared between
    // nodes in the same window (list items with list items).
    protected fun yOfText(text: String): Float =
        composeTestRule.onNodeWithText(text).fetchSemanticsNode().positionInRoot.y

    protected fun yOfTag(tag: String): Float =
        composeTestRule.onNodeWithTag(tag).fetchSemanticsNode().positionInRoot.y

    // Strict pairwise comparison of successive y positions: on failure the message names the
    // exact adjacent pair that is out of order, while the caller's expected list documents the
    // intended full sequence.
    protected fun assertDisplayedOrder(expected: List<String>) {
        for (i in 0 until expected.size - 1) {
            assertTrue(
                "Expected '${expected[i]}' above '${expected[i + 1]}'; displayed order mismatch",
                yOfText(expected[i]) < yOfText(expected[i + 1]),
            )
        }
    }

    protected data class CategoryRow(
        val id: Long,
        val name: String,
        val ordering: Int,
        val enabled: Boolean,
        val autoCorrect: Boolean = true,
        val capitalization: CapitalizationMode = CapitalizationMode.NONE,
    )

    protected fun row(
        id: Long,
        name: String,
        ordering: Int,
        enabled: Boolean = true,
        autoCorrect: Boolean = true,
        capitalization: CapitalizationMode = CapitalizationMode.NONE,
    ) = CategoryRow(id, name, ordering, enabled, autoCorrect, capitalization)

    // Compares the ENTIRE category table (in DAO sort order, so ordering values are pinned
    // too) against [expected]. Deliberate whole-collection equality so failures print both
    // complete tables rather than a message about one mismatched row.
    protected suspend fun assertWholeCategoryTable(
        expected: List<CategoryRow>,
        label: String = "whole category table",
    ) {
        val actual =
            categoryDao.getAllCategories().map {
                CategoryRow(
                    it.id,
                    it.name,
                    it.ordering,
                    it.enabled,
                    it.autoCorrect,
                    it.capitalization,
                )
            }
        assertEquals(label, expected, actual)
    }

    // Expected delete-dialog messages, built through the same resource/plural/locale machinery
    // the screen uses, so the expectations track device locale and plural rules instead of
    // hard-coding English strings (same trick as the date-picker test's FULL-format matcher).
    protected fun expectedNoEntriesMessage(): String =
        context.getString(R.string.delete_category_message_no_entries)

    protected fun expectedNoDateMessage(count: Int): String =
        context.resources.getQuantityString(
            R.plurals.delete_category_message_with_entries_no_date,
            count,
            count,
        )

    protected fun expectedRangeMessage(count: Int, earliestIso: String, latestIso: String): String {
        val formatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        return context.resources.getQuantityString(
            R.plurals.delete_category_message_with_entries_range,
            count,
            count,
            LocalDate.parse(earliestIso).format(formatter),
            LocalDate.parse(latestIso).format(formatter),
        )
    }
}
