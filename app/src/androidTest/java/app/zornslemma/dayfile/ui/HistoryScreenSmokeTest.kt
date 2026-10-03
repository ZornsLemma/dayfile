package app.zornslemma.dayfile.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.zornslemma.dayfile.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// End-to-end sanity check of the history screen: a single journey exercising the default view,
// category filtering, the include-deleted toggle, the stale-filter corner sequence and back
// navigation, all against real in-memory Room databases. Deliberately broad rather than deep:
// focused coverage of the individual behaviours lives in HistoryScreenBasicsTest and
// HistoryScreenFilterStateTest. If this test fails, run those first to localise the problem.
class HistoryScreenSmokeTest : BaseHistoryScreenTest() {

    @Test
    fun smokeTest_historyScreenJourney() {
        // Mixed seed: live enabled, live disabled, and archived (deleted) categories, with
        // insertion order deliberately scrambled relative to savedAt so display ordering is
        // driven purely by timestamps. Texts are pairwise non-prefix within each category so
        // the display-time prefix compactor cannot silently collapse any of them.
        seedCategory(id = 1, name = "Diet", ordering = 0)
        seedCategory(id = 2, name = "Money", ordering = 1)
        seedCategory(id = 3, name = "Exercise", ordering = 2)
        seedCategory(id = 4, name = "Retired", ordering = 3, enabled = false)
        seedArchivedCategory(id = 7, name = "Old Job")

        seedHistoryEntry(categoryId = 2, text = "4.88", savedAt = minutesAgo(100))
        seedHistoryEntry(categoryId = 1, text = "diet oldest", savedAt = minutesAgo(120))
        seedHistoryEntry(categoryId = 1, text = "diet newest", savedAt = minutesAgo(80))
        seedHistoryEntry(categoryId = 7, text = "old job note", savedAt = minutesAgo(60))
        seedHistoryEntry(categoryId = 4, text = "retired note", savedAt = minutesAgo(40))

        var backCount = 0
        launchHistoryScreen(onBack = { backCount++ })

        // Phase 1: defaults - only live enabled categories' entries, globally newest-first,
        // with interleaved per-category headers; deleted and disabled categories' entries
        // hidden entirely.
        val newestY = yOfText("diet newest")
        val middleY = yOfText("4.88")
        val oldestY = yOfText("diet oldest")
        assertTrue(
            "Expected newest-first across categories ($newestY, $middleY, $oldestY)",
            newestY < middleY && middleY < oldestY,
        )
        composeTestRule.onAllNodesWithTag("history_header_1").assertCountEquals(2)
        composeTestRule.onAllNodesWithTag("history_header_2").assertCountEquals(1)
        composeTestRule.onNodeWithText("old job note").assertDoesNotExist()
        composeTestRule.onNodeWithText("retired note").assertDoesNotExist()

        // Phase 2: filtering to one category narrows the list and drops the (redundant)
        // headers even for the selected category itself.
        selectFilterOption(2)
        assertFilterFieldShows("Money")
        composeTestRule.onNodeWithText("4.88").assertExists()
        composeTestRule.onNodeWithText("diet newest").assertDoesNotExist()
        composeTestRule.onNodeWithTag("history_header_2").assertDoesNotExist()

        // Phase 3: returning to "All" restores the full default view, headers included.
        selectFilterOption(null)
        assertFilterFieldShows(context.getString(R.string.category_all))
        composeTestRule.onNodeWithText("diet newest").assertExists()
        composeTestRule.onNodeWithText("4.88").assertExists()
        composeTestRule.onNodeWithText("diet oldest").assertExists()
        composeTestRule.onAllNodesWithTag("history_header_1").assertCountEquals(2)

        // Phase 4: including deleted categories reveals the archived category's entries under
        // its disambiguated label header.
        toggleIncludeDeleted()
        composeTestRule.onNodeWithText("old job note").assertExists()
        composeTestRule
            .onNodeWithTag("history_header_7")
            .assertTextEquals(
                context.getString(R.string.history_deleted_category_option, "Old Job", 7L)
            )

        // Phase 5: the corner sequence - select the deleted category, hide deleted categories
        // again (behaves like "All" AND heals the stored filter to null), then re-include them
        // (still "All": the deleted selection must not resurrect).
        selectFilterOption(7)
        assertFilterFieldShows(
            context.getString(R.string.history_deleted_category_option, "Old Job", 7L)
        )
        composeTestRule.onNodeWithText("old job note").assertExists()
        composeTestRule.onNodeWithText("diet newest").assertDoesNotExist()
        waitUntilRawFilterIs(7)

        toggleIncludeDeleted()
        assertFilterFieldShows(context.getString(R.string.category_all))
        composeTestRule.onNodeWithText("diet newest").assertExists()
        composeTestRule.onNodeWithText("old job note").assertDoesNotExist()
        waitUntilRawFilterIs(null)

        toggleIncludeDeleted()
        assertFilterFieldShows(context.getString(R.string.category_all))
        composeTestRule.onNodeWithText("old job note").assertExists()
        composeTestRule.onNodeWithText("diet newest").assertExists()
        waitUntilRawFilterIs(null)

        // Phase 6: back navigation hands control back to the caller exactly once.
        composeTestRule
            .onNodeWithContentDescription(context.getString(R.string.back_content_description))
            .performClick()
        composeTestRule.waitForIdle()

        assertEquals(1, backCount)
    }
}
