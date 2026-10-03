package app.zornslemma.dayfile.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.zornslemma.dayfile.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Focused, independent checks of individual history screen behaviours, mirroring the split used
// for the home screen: each test covers one thing so failures localise quickly, and
// HistoryScreenSmokeTest exercises the same behaviours together as a single journey.
class HistoryScreenBasicsTest : BaseHistoryScreenTest() {

    @Test
    fun emptyStateMessageShownWhenNoHistoryExists() {
        launchHistoryScreen()

        composeTestRule.onNodeWithText(context.getString(R.string.history_empty)).assertExists()
    }

    @Test
    fun emptyStateWordingDistinguishesFilteredCategoryFromAll() {
        seedCategory(id = 1, name = "Diet", ordering = 0)
        seedCategory(id = 2, name = "Money", ordering = 1)
        seedHistoryEntry(categoryId = 1, text = "salad", savedAt = minutesAgo(90))

        launchHistoryScreen()

        // With history present, neither empty-state message may appear.
        composeTestRule
            .onNodeWithText(context.getString(R.string.history_empty))
            .assertDoesNotExist()
        composeTestRule
            .onNodeWithText(context.getString(R.string.history_empty_filtered))
            .assertDoesNotExist()

        // Filtering to a category with no snapshots that day must use the filtered wording:
        // the date DOES have history, just not for the selected category, so the date-level
        // message would be misleading.
        selectFilterOption(2)
        composeTestRule
            .onNodeWithText(context.getString(R.string.history_empty_filtered))
            .assertExists()
        composeTestRule
            .onNodeWithText(context.getString(R.string.history_empty))
            .assertDoesNotExist()
    }

    @Test
    fun showsOnlyEntriesForTheViewedDate() {
        seedCategory(id = 1, name = "Diet", ordering = 0)
        seedHistoryEntry(categoryId = 1, text = "today snack", savedAt = minutesAgo(90))
        seedHistoryEntry(
            categoryId = 1,
            text = "yesterday snack",
            savedAt = minutesAgo(90),
            date = LocalDate.parse("2026-07-31"),
        )

        launchHistoryScreen()

        composeTestRule.onNodeWithText("today snack").assertExists()
        composeTestRule.onNodeWithText("yesterday snack").assertDoesNotExist()
    }

    @Test
    fun dropdownListsAllThenEnabledCategoriesInOrderingOrder() {
        seedCategory(id = 1, name = "Diet", ordering = 1)
        seedCategory(id = 2, name = "Money", ordering = 0)
        seedCategory(id = 3, name = "Exercise", ordering = 2)
        seedCategory(id = 4, name = "Retired", ordering = 3, enabled = false)

        launchHistoryScreen()
        openFilterDropdown()

        // Top-to-bottom: All, then enabled categories by user-defined ordering. Disabled
        // categories must not appear anywhere.
        val allY = yOfTag("history_filter_option_all")
        val moneyY = yOfTag("history_filter_option_2")
        val dietY = yOfTag("history_filter_option_1")
        val exerciseY = yOfTag("history_filter_option_3")
        assertTrue("All option should be first ($allY >= $moneyY)", allY < moneyY)
        assertTrue("Money should precede Diet ($moneyY >= $dietY)", moneyY < dietY)
        assertTrue("Diet should precede Exercise ($dietY >= $exerciseY)", dietY < exerciseY)
        composeTestRule.onAllNodesWithTag("history_filter_option_4").assertCountEquals(0)
        composeTestRule.onNodeWithText("Retired").assertDoesNotExist()
    }

    @Test
    fun deletedCategoriesAppearOnlyWhenIncludedLabelledAndSortedById() {
        seedCategory(id = 1, name = "Diet", ordering = 0)
        seedArchivedCategory(id = 9, name = "Zeta")
        seedArchivedCategory(id = 5, name = "Alpha")
        seedHistoryEntry(categoryId = 9, text = "old zeta note", savedAt = minutesAgo(90))
        seedHistoryEntry(categoryId = 5, text = "old alpha note", savedAt = minutesAgo(80))

        launchHistoryScreen()

        // Hidden by default: neither the dropdown options nor the entries themselves.
        composeTestRule.onAllNodesWithTag("history_filter_option_9").assertCountEquals(0)
        composeTestRule.onNodeWithText("old zeta note").assertDoesNotExist()
        composeTestRule.onNodeWithText("old alpha note").assertDoesNotExist()

        toggleIncludeDeleted()

        composeTestRule.onNodeWithText("old zeta note").assertExists()
        composeTestRule.onNodeWithText("old alpha note").assertExists()

        // Deleted categories also render their disambiguated label as list headers above
        // their entries.
        composeTestRule.onNodeWithTag("history_header_9").assertExists()
        composeTestRule.onNodeWithTag("history_header_5").assertExists()

        // Labels come from resources so the test tracks the user-visible wording exactly.
        // Matched via the options' tags rather than by text: with the dropdown open the same
        // label legitimately exists twice (list header AND menu item), so a bare text match
        // would be ambiguous.
        openFilterDropdown()
        composeTestRule
            .onNodeWithTag("history_filter_option_9")
            .assertTextEquals(
                context.getString(R.string.history_deleted_category_option, "Zeta", 9L)
            )
        composeTestRule
            .onNodeWithTag("history_filter_option_5")
            .assertTextEquals(
                context.getString(R.string.history_deleted_category_option, "Alpha", 5L)
            )

        // Deleted options sort by id (5 before 9), after the live ones.
        val dietY = yOfTag("history_filter_option_1")
        val alphaY = yOfTag("history_filter_option_5")
        val zetaY = yOfTag("history_filter_option_9")
        assertTrue("Live should precede deleted ($dietY >= $alphaY)", dietY < alphaY)
        assertTrue("Deleted should sort by id ($alphaY >= $zetaY)", alphaY < zetaY)
    }

    @Test
    fun disabledLiveCategoryIsNotReintroducedFromItsArchiveRow() {
        // Present in BOTH tables: live but disabled, with a leftover archive row. The merge
        // logic must not turn the archive row into a dropdown option (that would resurrect a
        // category the user deliberately disabled), and its entries must resolve to nothing.
        seedCategory(id = 3, name = "Retired", ordering = 0, enabled = false)
        seedArchivedCategory(id = 3, name = "Retired")
        seedHistoryEntry(categoryId = 3, text = "retired note", savedAt = minutesAgo(90))
        seedCategory(id = 1, name = "Diet", ordering = 1)
        seedHistoryEntry(categoryId = 1, text = "diet note", savedAt = minutesAgo(80))

        launchHistoryScreen()
        toggleIncludeDeleted()
        openFilterDropdown()

        composeTestRule.onAllNodesWithTag("history_filter_option_3").assertCountEquals(0)
        composeTestRule.onNodeWithText("Retired").assertDoesNotExist()

        // Its history entries stay invisible even with deleted categories included, while a
        // normal entry still displays.
        composeTestRule.onNodeWithText("retired note").assertDoesNotExist()
        composeTestRule.onNodeWithText("diet note").assertExists()
    }

    @Test
    fun renamedCategoryUpdatesHeadersAndOptionsLive() {
        seedCategory(id = 1, name = "Diet", ordering = 0)
        seedHistoryEntry(categoryId = 1, text = "salad", savedAt = minutesAgo(90))

        launchHistoryScreen()

        composeTestRule.onNodeWithTag("history_header_1").assertTextEquals("Diet")

        // Rename through the real repository path (which also syncs the history archive row).
        // Nothing in the normal navigation can rename a category while History is open, but the
        // ViewModel is deliberately flow-driven, so a rename landing around screen-open time
        // must be picked up rather than rendered stale.
        runBlocking {
            val diet = categoryDao.getCategory(1L)!!
            categoryRepository.upsertCategory(
                name = "Food",
                autoCorrect = diet.autoCorrect,
                capitalization = diet.capitalization,
                categoryId = 1L,
            )
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("history_header_1").assertTextEquals("Food")
        composeTestRule.onNodeWithText("Diet").assertDoesNotExist()
        composeTestRule.onNodeWithText("salad").assertExists()

        openFilterDropdown()
        composeTestRule.onNodeWithTag("history_filter_option_1").assertTextEquals("Food")
    }

    @Test
    fun selectingCategoryFiltersListAndAllRestoresIt() {
        seedCategory(id = 1, name = "Diet", ordering = 0)
        seedCategory(id = 2, name = "Money", ordering = 1)
        seedHistoryEntry(categoryId = 1, text = "salad", savedAt = minutesAgo(90))
        seedHistoryEntry(categoryId = 2, text = "4.88", savedAt = minutesAgo(80))

        launchHistoryScreen()

        composeTestRule.onNodeWithText("salad").assertExists()
        composeTestRule.onNodeWithText("4.88").assertExists()

        selectFilterOption(1)

        // Field display checked via EditableText (see assertFilterFieldShows): the merged
        // TextField node also carries the "Category" label under Text, so assertTextEquals
        // on the whole node can never equal just the selected value.
        assertFilterFieldShows("Diet")
        composeTestRule.onNodeWithText("salad").assertExists()
        composeTestRule.onNodeWithText("4.88").assertDoesNotExist()

        selectFilterOption(null)

        assertFilterFieldShows(context.getString(R.string.category_all))
        composeTestRule.onNodeWithText("salad").assertExists()
        composeTestRule.onNodeWithText("4.88").assertExists()
    }

    @Test
    fun categoryHeadersShownOnlyInAllView() {
        seedCategory(id = 1, name = "Diet", ordering = 0)
        seedCategory(id = 2, name = "Money", ordering = 1)
        seedHistoryEntry(categoryId = 1, text = "salad", savedAt = minutesAgo(90))
        seedHistoryEntry(categoryId = 2, text = "4.88", savedAt = minutesAgo(80))

        launchHistoryScreen()

        composeTestRule.onNodeWithTag("history_header_1").assertExists()
        composeTestRule.onNodeWithTag("history_header_2").assertExists()

        selectFilterOption(1)

        // A single-category view makes headings redundant: none at all, not even the
        // selected category's own.
        composeTestRule.onNodeWithTag("history_header_1").assertDoesNotExist()
        composeTestRule.onNodeWithTag("history_header_2").assertDoesNotExist()
        composeTestRule.onNodeWithText("salad").assertExists()
    }

    @Test
    fun entriesSortNewestFirstAcrossCategoriesAndRegroupHeaders() {
        seedCategory(id = 1, name = "Diet", ordering = 0)
        seedCategory(id = 2, name = "Money", ordering = 1)
        // Insertion order deliberately differs from display order: savedAt alone drives it.
        seedHistoryEntry(categoryId = 1, text = "diet oldest", savedAt = minutesAgo(100))
        seedHistoryEntry(categoryId = 2, text = "money middle", savedAt = minutesAgo(90))
        seedHistoryEntry(categoryId = 1, text = "diet newest", savedAt = minutesAgo(80))

        launchHistoryScreen()

        // Global newest-first across categories (not grouped per category).
        val newestY = yOfText("diet newest")
        val middleY = yOfText("money middle")
        val oldestY = yOfText("diet oldest")
        assertTrue(
            "Expected newest-first across categories " + "($newestY, $middleY, $oldestY)",
            newestY < middleY && middleY < oldestY,
        )

        // Interleaved categories produce three header groups: Diet, Money, Diet.
        composeTestRule.onAllNodesWithTag("history_header_1").assertCountEquals(2)
        composeTestRule.onAllNodesWithTag("history_header_2").assertCountEquals(1)

        // Each group's header sits between the entry above it and its own first entry.
        val dietHeaders =
            composeTestRule.onAllNodesWithTag("history_header_1").fetchSemanticsNodes()
        val moneyHeaderY = yOfTag("history_header_2")
        assertTrue(
            "First Diet header should sit above 'diet newest'",
            dietHeaders[0].positionInRoot.y < newestY,
        )
        assertTrue(
            "Money header should sit between the Diet groups",
            newestY < moneyHeaderY && moneyHeaderY < middleY,
        )
        assertTrue(
            "Second Diet header should sit above 'diet oldest'",
            dietHeaders[1].positionInRoot.y < oldestY && dietHeaders[1].positionInRoot.y > middleY,
        )
    }

    @Test
    fun backButtonInvokesOnBack() {
        var backCount = 0
        launchHistoryScreen(onBack = { backCount++ })

        composeTestRule
            .onNodeWithContentDescription(context.getString(R.string.back_content_description))
            .performClick()
        composeTestRule.waitForIdle()

        assertEquals(1, backCount)
    }

    @Test
    fun orphanedHistoryRowsAreDroppedWithoutCrashing() {
        seedCategory(id = 1, name = "Diet", ordering = 0)
        seedHistoryEntry(categoryId = 1, text = "real note", savedAt = minutesAgo(90))
        // No live category and no archive row exists for id 999. Can arise transiently or via
        // restore; the row must vanish quietly rather than crash or render blankly.
        seedHistoryEntry(categoryId = 999, text = "ghost note", savedAt = minutesAgo(80))

        launchHistoryScreen()

        composeTestRule.onNodeWithText("real note").assertExists()
        composeTestRule.onNodeWithText("ghost note").assertDoesNotExist()
        // The list is not empty overall, so the empty-state message must not appear either.
        composeTestRule
            .onNodeWithText(context.getString(R.string.history_empty))
            .assertDoesNotExist()
    }

    @Test
    fun prefixCollapseHidesSupersededShorterSnapshotsInDisplayOnly() {
        seedCategory(id = 1, name = "Diet", ordering = 0)
        // A typing burst as history would have captured it: every snapshot is stored, but the
        // display must collapse the prefix chain to the most complete text while keeping
        // divergent snapshots visible.
        seedHistoryEntry(categoryId = 1, text = "Phone num", savedAt = minutesAgo(100))
        seedHistoryEntry(categoryId = 1, text = "Phone number", savedAt = minutesAgo(80))
        seedHistoryEntry(categoryId = 1, text = "Phone alt", savedAt = minutesAgo(60))

        launchHistoryScreen()

        composeTestRule.onNodeWithText("Phone number").assertExists()
        composeTestRule.onNodeWithText("Phone alt").assertExists()
        composeTestRule.onNodeWithText("Phone num").assertDoesNotExist()
        composeTestRule.onAllNodesWithTag("history_header_1").assertCountEquals(1)

        // The collapse is a display-time filter only: every snapshot remains in the database.
        runBlocking {
            assertEquals(
                3,
                historyDao.observeHistoryForDateAndCategory(historyDate, 1L).first().size,
            )
        }
    }

    @Test
    fun entryShowsAbsoluteEditTimestamp() {
        seedCategory(id = 1, name = "Diet", ordering = 0)
        val savedAt = minutesAgo(90)
        seedHistoryEntry(categoryId = 1, text = "salad", savedAt = savedAt)

        launchHistoryScreen()

        // Compute the expected absolute portion with the exact formatter and zone the screen
        // uses (same technique as the home date-picker test's FULL-format matcher), so the
        // assertion tracks locale without hard-coding a formatted string. The relative-time
        // half of the line is inherently time-dependent and is deliberately not asserted.
        val expectedAbsTime =
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
                .format(Instant.ofEpochMilli(savedAt).atZone(ZoneId.systemDefault()))
        composeTestRule.onNodeWithText(expectedAbsTime, substring = true).assertExists()
    }
}
