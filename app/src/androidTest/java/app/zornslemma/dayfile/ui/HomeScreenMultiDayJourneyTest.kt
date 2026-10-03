package app.zornslemma.dayfile.ui

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextClearance
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlinx.coroutines.runBlocking
import org.junit.Test

// A scripted, fully deterministic multi-day journey: typing on several days, navigating back and
// forth, editing reseeded fields and clearing a field, with the whole database verified against a
// simple shadow model after every leg. Deliberately hand-written rather than random so that, if
// something breaks, this is the easier test to debug; a seeded random sweep only needs attention
// if this one is clean and something still fails.
//
// All typing uses plain appends: a freshly created field seeds its cursor at the end of its
// initial text and typing leaves it there, so the final database content encodes the entire edit
// history of each field - a lost write anywhere in the journey breaks an assertion.
class HomeScreenMultiDayJourneyTest : BaseHomeScreenTest() {

    // Fixed clock: noon, day-start 04:00 => logical date == calendar date, deterministic
    // whenever the test runs.
    private val now = LocalDateTime.of(2026, 8, 1, 12, 0)
    private val today = HomeLogic.logicalDateFor(now, LocalTime.of(4, 0))
    private val day1 = today.plusDays(1)
    private val day2 = today.plusDays(2)
    private val dayMinus1 = today.minusDays(1)
    private val dayMinus2 = today.minusDays(2)

    // Shadow model of the entry table, keyed by (categoryId, date). Updated by the same
    // operations the test performs on the UI, and compared against the whole database after
    // every leg so any failure localises to a single step.
    private val model = mutableMapOf<Pair<Long, LocalDate>, String>()

    private fun append(catId: Long, date: LocalDate, token: String) {
        type(catId, token)
        model[catId to date] = (model[catId to date] ?: "") + token
    }

    private fun clearField(catId: Long, date: LocalDate) {
        composeTestRule.onNodeWithTag("entry_textfield_$catId").performTextClearance()
        composeTestRule.waitForIdle()
        model.remove(catId to date)
    }

    private fun verifyModel(label: String) {
        runBlocking { assertWholeDb(model.toMap(), label) }
    }

    // Protection is not part of the model: it is a pure function of which day we are on, so each
    // arrival asserts it directly. This repeatedly covers the SPEC rules that moving to another
    // day always resets to protected, and that the current day shows no icon at all.
    private fun assertProtection(expectedProtected: Boolean) {
        if (expectedProtected) {
            composeTestRule.onNodeWithContentDescription(protectedDesc).assertExists()
        } else {
            composeTestRule.onNodeWithContentDescription(protectedDesc).assertDoesNotExist()
        }
    }

    @Test
    fun multiDayJourney_preservesEditsAcrossNavigation() {
        seedCategory(id = 1, name = "Diet", ordering = 0)
        seedCategory(id = 2, name = "Money", ordering = 1)

        launchHomeScreen(buildHomeViewModel { now })
        assertProtection(expectedProtected = false)
        verifyModel("initial load")

        // Today: first entry.
        append(1L, today, "salad")
        verifyModel("after typing on today")

        // Tomorrow (+1): unlock, type into both categories.
        goNext()
        assertProtection(expectedProtected = true)
        unlockIfProtected()
        append(1L, day1, "lunch")
        append(2L, day1, "bus")
        verifyModel("after typing on +1")

        // Day +2: unlock, type into one category.
        goNext()
        assertProtection(expectedProtected = true)
        unlockIfProtected()
        append(2L, day2, "trip")
        verifyModel("after typing on +2")

        // Back to +1: field reseeded from the database, then edited by appending.
        goPrev()
        assertProtection(expectedProtected = true)
        unlockIfProtected()
        composeTestRule.onNodeWithTag("entry_textfield_1").assertTextEquals("lunch")
        append(1L, day1, "More")
        verifyModel("after editing reseeded +1 field")

        // Back to today: unlocked, existing field appended to.
        goPrev()
        assertProtection(expectedProtected = false)
        composeTestRule.onNodeWithTag("entry_textfield_1").assertTextEquals("salad")
        append(1L, today, "Pie")
        verifyModel("after editing reseeded today field")

        // Yesterday (-1): unlock, new entry.
        goPrev()
        assertProtection(expectedProtected = true)
        unlockIfProtected()
        append(1L, dayMinus1, "yest")
        verifyModel("after typing on -1")

        // Day -2: nothing has ever been typed here; field must be empty, database untouched.
        goPrev()
        assertProtection(expectedProtected = true)
        composeTestRule.onNodeWithTag("entry_textfield_1").assertTextEquals("")
        verifyModel("after visiting empty -2")

        // Sweep forward again to +2, checking that protection resets on every arrival.
        goNext() // -1
        assertProtection(expectedProtected = true)
        goNext() // today
        assertProtection(expectedProtected = false)
        goNext() // +1
        assertProtection(expectedProtected = true)
        goNext() // +2
        assertProtection(expectedProtected = true)

        // The +2 field must have reseeded correctly; then clear it, which must delete the row.
        unlockIfProtected()
        composeTestRule.onNodeWithTag("entry_textfield_2").assertTextEquals("trip")
        clearField(2L, day2)
        composeTestRule.onNodeWithTag("entry_textfield_2").assertTextEquals("")
        verifyModel("after clearing the +2 field")

        // Final state: back to today, everything still intact.
        goPrev() // +1
        goPrev() // today
        assertProtection(expectedProtected = false)
        composeTestRule.onNodeWithTag("entry_textfield_1").assertTextEquals("saladPie")
        verifyModel("final state")
    }
}
