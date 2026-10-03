package app.zornslemma.dayfile.ui

import androidx.compose.ui.test.onNodeWithText
import app.zornslemma.dayfile.R
import kotlinx.coroutines.runBlocking
import org.junit.Test

// Covers the persisted history filter state (AppStateRepository/DataStore) beyond what
// HistoryScreenBasicsTest exercises: the ViewModel's self-healing of filters that stop resolving
// (via the interactive toggle, out-of-band category deletion or disabling, and a stale value left
// by simulated process death), plus persistence of a deliberate selection across closing and
// reopening the screen. Every healing path ends at the same invariant: the stored filter always
// matches what the user last saw, so a deleted-category selection can never silently resurrect.
class HistoryScreenFilterStateTest : BaseHistoryScreenTest() {

    @Test
    fun togglingIncludeDeletedOffThenOnDoesNotResurrectDeletedFilter() {
        seedCategory(id = 1, name = "Diet", ordering = 0)
        seedArchivedCategory(id = 9, name = "Zeta")
        seedHistoryEntry(categoryId = 1, text = "diet note", savedAt = minutesAgo(90))
        seedHistoryEntry(categoryId = 9, text = "zeta note", savedAt = minutesAgo(80))

        launchHistoryScreen()

        // Select the deleted category while it is visible.
        toggleIncludeDeleted()
        selectFilterOption(9)
        waitUntilRawFilterIs(9)
        assertFilterFieldShows(
            context.getString(R.string.history_deleted_category_option, "Zeta", 9L)
        )
        composeTestRule.onNodeWithText("zeta note").assertExists()
        composeTestRule.onNodeWithText("diet note").assertDoesNotExist()

        // Hiding deleted categories must behave like "All" AND clear the now-stale persisted
        // filter (the ViewModel self-heals; there is no screen-side reset any more).
        toggleIncludeDeleted()
        // Wait for the heal BEFORE asserting the UI. The self-heal is a second, separate
        // consequence of the toggle: the include-deleted write lands first, and only then does
        // the ViewModel notice the stored filter no longer resolves and write back. Waiting here
        // rather than after the assertions is what stops the display assertion racing it.
        waitUntilRawFilterIs(null)
        assertFilterFieldShows(context.getString(R.string.category_all))
        composeTestRule.onNodeWithText("diet note").assertExists()
        composeTestRule.onNodeWithText("zeta note").assertDoesNotExist()

        // Toggling back on must NOT restore the deleted selection: it sticks on "All".
        toggleIncludeDeleted()
        assertFilterFieldShows(context.getString(R.string.category_all))
        composeTestRule.onNodeWithText("zeta note").assertExists()
        composeTestRule.onNodeWithText("diet note").assertExists()
        // A negative invariant ("must not resurrect"), so this is a confirmation that the value is
        // still null rather than a wait for a change - polling cannot strengthen a "stays" claim.
        waitUntilRawFilterIs(null)
    }

    @Test
    fun deletingFilteredCategoryOutOfBandHealsAndPreventsResurrection() {
        seedCategory(id = 1, name = "Diet", ordering = 0)
        seedCategory(id = 2, name = "Money", ordering = 1)
        seedHistoryEntry(categoryId = 1, text = "salad", savedAt = minutesAgo(90))
        seedHistoryEntry(categoryId = 2, text = "4.88", savedAt = minutesAgo(80))

        launchHistoryScreen()

        selectFilterOption(2)
        waitUntilRawFilterIs(2)
        assertFilterFieldShows("Money")
        composeTestRule.onNodeWithText("4.88").assertExists()
        composeTestRule.onNodeWithText("salad").assertDoesNotExist()

        // Delete the filtered category out-of-band through the real repository, exactly as the
        // categories screen does (including its archive-row-before-delete ordering).
        runBlocking { categoryRepository.deleteCategory(categoryDao.getCategory(2L)!!) }
        composeTestRule.waitForIdle()

        // The filter no longer resolves: the display falls back to "All" and the persisted
        // value heals to match. Wait for the heal before asserting the display - the Room
        // invalidation, the ViewModel's re-resolve and its write-back are all async, and
        // waitForIdle() covers none of them.
        waitUntilRawFilterIs(null)
        assertFilterFieldShows(context.getString(R.string.category_all))
        composeTestRule.onNodeWithText("salad").assertExists()
        composeTestRule.onNodeWithText("4.88").assertDoesNotExist()

        // Regression guard: re-including deleted categories must not resurrect the deleted
        // filter.
        toggleIncludeDeleted()
        assertFilterFieldShows(context.getString(R.string.category_all))
        composeTestRule.onNodeWithText("salad").assertExists()
        composeTestRule.onNodeWithText("4.88").assertExists()
        // A negative invariant ("must not resurrect"), so a confirmation rather than a wait for a
        // change: polling cannot strengthen a "stays" claim.
        waitUntilRawFilterIs(null)
    }

    @Test
    fun disablingFilteredCategoryOutOfBandHealsAndReenablingRestoresEntries() {
        seedCategory(id = 1, name = "Diet", ordering = 0)
        seedCategory(id = 2, name = "Money", ordering = 1)
        seedHistoryEntry(categoryId = 1, text = "salad", savedAt = minutesAgo(90))
        seedHistoryEntry(categoryId = 2, text = "4.88", savedAt = minutesAgo(80))

        launchHistoryScreen()

        selectFilterOption(1)
        waitUntilRawFilterIs(1)
        assertFilterFieldShows("Diet")

        // Disable out-of-band, mirroring the categories screen's direct table update. Unlike
        // deletion this is reversible, and disabled categories are never visible in history
        // (not even with "include deleted" on), so this behaviour is pinned independently
        // rather than assumed equivalent to the deletion case.
        runBlocking {
            val diet = categoryDao.getCategory(1L)!!
            categoryDao.updateCategories(listOf(diet.copy(enabled = false)))
        }
        composeTestRule.waitForIdle()

        // Entries vanish, the display falls back to "All" and the persisted filter heals. Wait
        // for the heal first: waitForIdle() does not cover the Room invalidation, the ViewModel's
        // re-resolve, or its write-back.
        waitUntilRawFilterIs(null)
        assertFilterFieldShows(context.getString(R.string.category_all))
        composeTestRule.onNodeWithText("salad").assertDoesNotExist()
        composeTestRule.onNodeWithText("4.88").assertExists()

        // Re-enabling brings the entries back; the healed filter stays healed (still "All")
        // rather than resurrecting the pre-disable selection.
        runBlocking {
            val diet = categoryDao.getCategory(1L)!!
            categoryDao.updateCategories(listOf(diet.copy(enabled = true)))
        }
        composeTestRule.waitForIdle()

        assertFilterFieldShows(context.getString(R.string.category_all))
        composeTestRule.onNodeWithText("salad").assertExists()
        composeTestRule.onNodeWithText("4.88").assertExists()
        // A negative invariant ("stays healed"), so a confirmation rather than a wait.
        waitUntilRawFilterIs(null)
    }

    @Test
    fun staleFilterSeededBeforeLaunchHealsAndStaysHealed() {
        // Simulates process death with a deleted-category filter persisted while "include
        // deleted" was off: the raw value survives in DataStore but matches nothing selectable
        // on relaunch.
        seedCategory(id = 1, name = "Diet", ordering = 0)
        seedArchivedCategory(id = 9, name = "Zeta")
        seedHistoryEntry(categoryId = 1, text = "diet note", savedAt = minutesAgo(90))
        seedHistoryEntry(categoryId = 9, text = "zeta note", savedAt = minutesAgo(80))

        runBlocking {
            appStateRepository.setHistoryFilterCategory(9)
            appStateRepository.setHistoryFilterIncludeDeleted(false)
        }

        launchHistoryScreen()

        // Display correctly falls back to "All" and the ViewModel heals the stored value. The
        // heal is async - the ViewModel has to build, observe the stale value against the live
        // category list, and write back - so wait for it before asserting the display.
        waitUntilRawFilterIs(null)
        assertFilterFieldShows(context.getString(R.string.category_all))
        composeTestRule.onNodeWithText("diet note").assertExists()
        composeTestRule.onNodeWithText("zeta note").assertDoesNotExist()

        // Re-including deleted categories must not bring the stale selection back.
        // toggleIncludeDeleted waits for its own write, which is what this block depends on.
        toggleIncludeDeleted()
        assertFilterFieldShows(context.getString(R.string.category_all))
        composeTestRule.onNodeWithText("zeta note").assertExists()
        // A negative invariant ("must not bring back"), so a confirmation rather than a wait.
        waitUntilRawFilterIs(null)
    }

    @Test
    fun filterSelectionSurvivesClosingAndReopeningTheScreen() {
        seedCategory(id = 1, name = "Diet", ordering = 0)
        seedCategory(id = 2, name = "Money", ordering = 1)
        seedHistoryEntry(categoryId = 1, text = "salad", savedAt = minutesAgo(90))
        seedHistoryEntry(categoryId = 2, text = "4.88", savedAt = minutesAgo(80))

        launchHistoryScreen()
        selectFilterOption(1)
        assertFilterFieldShows("Diet")
        waitUntilRawFilterIs(1)

        // "Close" and reopen: the screen's nav-scoped ViewModel is destroyed and a brand-new
        // one built over the same repositories, which must inherit the persisted selection.
        // (The 10-minute background reset is a separate mechanism, deliberately not exercised
        // here.)
        reopenHistoryScreen()

        assertFilterFieldShows("Diet")
        composeTestRule.onNodeWithText("salad").assertExists()
        composeTestRule.onNodeWithText("4.88").assertDoesNotExist()
    }
}
