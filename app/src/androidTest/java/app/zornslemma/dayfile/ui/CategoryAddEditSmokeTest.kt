package app.zornslemma.dayfile.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.zornslemma.dayfile.R
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

// End-to-end sanity check of the add/edit flow INCLUDING the list<->form navigation wiring,
// which the isolated basics tests cannot see (they launch the form directly). The Compose test
// rule permits only ONE setContent per test method, so instead of real navigation we render a
// tiny two-way switcher: a state variable decides whether the list or the form is composed, and
// the screens' own callbacks flip it - faithfully reproducing what MainActivity's NavHost does
// (including sharing ONE CategoryEditViewModel instance across both destinations, as the real
// nav graph does).
class CategoryAddEditSmokeTest : BaseCategoryScreenTest() {

    @Test
    fun smokeTest_addEditDuplicateDiscardJourney() {
        seedCategory(id = 1, name = "Money", ordering = 0)
        seedCategory(id = 2, name = "Diet", ordering = 1)

        val viewModel = buildCategoryEditViewModel()
        // Sentinel for the FAB-driven add route (no real category has this id).
        val addSentinel = Long.MIN_VALUE
        val editing = mutableStateOf<Long?>(null)

        // Same first-emission wait as launchCategoryAddEditScreen: the form captures its
        // initial values at first composition, so the shared flow must be loaded beforehand.
        composeTestRule.waitUntil(timeoutMillis = 5_000) { viewModel.categories.value.isNotEmpty() }

        composeTestRule.setContent {
            val target = editing.value
            if (target == null) {
                CategoryScreen(
                    viewModel = viewModel,
                    onBack = {},
                    onAddCategory = { editing.value = addSentinel },
                    onEditCategory = { editing.value = it },
                )
            } else {
                CategoryAddEditScreen(
                    viewModel = viewModel,
                    categoryId = target.takeIf { it != addSentinel },
                    onBack = { editing.value = null },
                )
            }
        }
        composeTestRule.waitForIdle()

        // Phase 1: list renders; the FAB opens the add form.
        assertDisplayedOrder(listOf("Money", "Diet"))
        composeTestRule
            .onNodeWithContentDescription(
                context.getString(R.string.add_category_content_description)
            )
            .performClick()
        composeTestRule.waitForIdle()
        composeTestRule
            .onNodeWithText(context.getString(R.string.add_category_title))
            .assertExists()

        // Phase 2: create "Snacks"; saving returns to the list, which now shows it last.
        typeCategoryName("Snacks")
        clickSave()
        waitUntilCategoryCount(3)
        composeTestRule
            .onNodeWithText(context.getString(R.string.add_category_title))
            .assertDoesNotExist()
        assertDisplayedOrder(listOf("Money", "Diet", "Snacks"))

        // Phase 3: edit Snacks via its menu; the form prefills, renaming round-trips.
        openItemMenu(3)
        clickMenuItem(R.string.edit)
        composeTestRule
            .onNodeWithText(context.getString(R.string.edit_category_title))
            .assertExists()
        assertNameFieldShows("Snacks")
        replaceCategoryName("Treats")
        clickSave()
        waitUntilCategoryNamed("Treats")
        assertDisplayedOrder(listOf("Money", "Diet", "Treats"))
        runBlocking {
            assertEquals(
                "Treats",
                historyDao.observeAllCategories().first().first { it.id == 3L }.name,
            )
        }

        // Phase 4: duplicate rejection - editing Diet to "money" clashes case-insensitively
        // with Money, so Save refuses and we stay on the form.
        openItemMenu(2)
        clickMenuItem(R.string.edit)
        replaceCategoryName("money")
        clickSave()
        composeTestRule
            .onNodeWithText(context.getString(R.string.category_name_error_duplicate))
            .assertExists()
        composeTestRule
            .onNodeWithText(context.getString(R.string.edit_category_title))
            .assertExists()

        // Phase 5: closing the dirty form offers the discard dialog; Cancel keeps us here,
        // Discard returns to the list with Diet untouched.
        clickClose()
        composeTestRule
            .onNodeWithText(context.getString(R.string.discard_changes_title))
            .assertExists()
        composeTestRule.onNodeWithText(context.getString(android.R.string.cancel)).performClick()
        composeTestRule.waitForIdle()
        composeTestRule
            .onNodeWithText(context.getString(R.string.edit_category_title))
            .assertExists()

        clickClose()
        composeTestRule.onNodeWithText(context.getString(R.string.discard)).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(context.getString(R.string.categories_title)).assertExists()
        runBlocking {
            assertWholeCategoryTable(
                listOf(row(1, "Money", 0), row(2, "Diet", 1), row(3, "Treats", 2)),
                "final state after journey",
            )
        }
    }
}
