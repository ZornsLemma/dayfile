package app.zornslemma.dayfile.ui

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.zornslemma.dayfile.R
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

// End-to-end sanity check of the category management screen: a single journey exercising
// rendering, enable/disable persistence, menu-driven reordering, the delete dialog and the
// history-archive side effect, all against real in-memory Room databases. Deliberately broad
// rather than deep: focused coverage of the individual behaviours lives in
// CategoryScreenBasicsTest. If this test fails, run the basics tests first to localise the
// problem.
class CategoryScreenSmokeTest : BaseCategoryScreenTest() {

    @Test
    fun smokeTest_categoryManagementJourney() {
        seedCategory(id = 1, name = "Diet", ordering = 0)
        seedCategory(id = 2, name = "Money", ordering = 1)
        seedCategory(id = 3, name = "Retired", ordering = 2, enabled = false)

        var backClicked = 0
        var addClicked = 0
        val editedIds = mutableListOf<Long>()

        launchCategoryScreen(
            onBack = { backClicked++ },
            onAddCategory = { addClicked++ },
            onEditCategory = { editedIds.add(it) },
        )

        // Phase 1: rendering - all categories shown in ordering order, including the disabled
        // one (dimmed on this screen, unlike the home screen which hides it).
        assertDisplayedOrder(listOf("Diet", "Money", "Retired"))
        composeTestRule.onNodeWithTag("category_switch_3").assertIsOff()

        // Phase 2: enabling the retired category persists immediately.
        toggleEnabled(3)
        runBlocking {
            assertWholeCategoryTable(
                listOf(
                    row(1, "Diet", 0, enabled = true),
                    row(2, "Money", 1, enabled = true),
                    row(3, "Retired", 2, enabled = true),
                )
            )
        }

        // Phase 3: moving Money down via its menu persists the new ordering.
        openItemMenu(2)
        clickMenuItem(R.string.move_down)
        val afterMoveDown =
            listOf(
                row(1, "Diet", 0, enabled = true),
                row(3, "Retired", 1, enabled = true),
                row(2, "Money", 2, enabled = true),
            )
        // The reorder write lands on Room's executor; wait for it before reading the table.
        waitUntilCategoryOrder(afterMoveDown)
        assertDisplayedOrder(listOf("Diet", "Retired", "Money"))
        runBlocking { assertWholeCategoryTable(afterMoveDown) }

        // Phase 4: disabling then deleting Money (it has no entries, so the simple dialog
        // variant appears). Deletion must remove the live row AND archive the name into the
        // history database's category map.
        toggleEnabled(2)
        openItemMenu(2)
        clickMenuItem(R.string.delete)
        composeTestRule.onNodeWithText(expectedNoEntriesMessage()).assertExists()
        confirmDeleteDialog()

        composeTestRule.onNodeWithText("Money").assertDoesNotExist()
        assertDisplayedOrder(listOf("Diet", "Retired"))
        runBlocking {
            assertWholeCategoryTable(
                listOf(row(1, "Diet", 0, enabled = true), row(3, "Retired", 1, enabled = true)),
                "Money deleted",
            )
            val archived = historyDao.observeAllCategories().first()
            assertEquals("Money", archived.first { it.id == 2L }.name)
        }

        // Phase 5: navigation and edit callbacks fire with the right arguments.
        openItemMenu(1)
        clickMenuItem(R.string.edit)
        assertEquals(listOf(1L), editedIds)

        composeTestRule
            .onNodeWithContentDescription(
                context.getString(R.string.add_category_content_description)
            )
            .performClick()
        composeTestRule.waitForIdle()
        composeTestRule
            .onNodeWithContentDescription(context.getString(R.string.back_content_description))
            .performClick()
        composeTestRule.waitForIdle()

        assertEquals(1, addClicked)
        assertEquals(1, backClicked)
    }
}
