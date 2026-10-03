package app.zornslemma.dayfile.ui

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import java.time.LocalDateTime
import java.time.LocalTime
import kotlinx.coroutines.runBlocking
import org.junit.Test

// End-to-end sanity check of the home screen: a single journey exercising rendering, immediate
// persistence, day navigation, field reseeding from the database and history capture, all against
// real in-memory Room databases. Deliberately broad rather than deep: focused coverage of the
// individual behaviours lives in HomeScreenBasicsTest. If this test fails, run the basics tests
// first to localise the problem.
class HomeScreenSmokeTest : BaseHomeScreenTest() {

    // Fixed clock: noon, day-start 04:00 => logical date == calendar date, deterministic
    // whenever the test runs.
    private val now = LocalDateTime.of(2026, 8, 1, 12, 0)
    private val today = HomeLogic.logicalDateFor(now, LocalTime.of(4, 0))
    private val tomorrow = today.plusDays(1)

    @Test
    fun smokeTest_homeScreenJourney() {
        seedCategory(id = 1, name = "Diet", ordering = 0)
        seedCategory(id = 2, name = "Money", ordering = 1)
        seedCategory(id = 3, name = "Retired", ordering = 2, enabled = false)

        launchHomeScreen(buildHomeViewModel { now })

        // Phase 1: rendering - enabled categories shown, disabled hidden.
        composeTestRule.onNodeWithText("Diet").assertExists()
        composeTestRule.onNodeWithText("Money").assertExists()
        composeTestRule.onNodeWithText("Retired").assertDoesNotExist()

        // Phase 2: typing persists immediately (entries are not debounced).
        type(1L, "salad")
        runBlocking { assertEntry(1L, today, "salad") }

        // Phase 3: the next day starts fresh and protected.
        goNext()
        composeTestRule.onNodeWithContentDescription(protectedDesc).assertExists()
        composeTestRule.onNodeWithTag("entry_textfield_1").assertTextEquals("")
        runBlocking { assertEntry(1L, tomorrow, null) }

        // Phase 4: returning to today restores the field from the database and drops protection.
        goPrev()
        composeTestRule.onNodeWithContentDescription(protectedDesc).assertDoesNotExist()
        composeTestRule.onNodeWithTag("entry_textfield_1").assertTextEquals("salad")
        runBlocking { assertEntry(1L, today, "salad") }

        // Phase 5: a second category on the same day.
        type(2L, "4.88")
        runBlocking { assertEntry(2L, today, "4.88") }

        // Phase 6: history captured for both categories. History writes are debounced
        // (delay-based on the real main dispatcher - the Compose test clock advances frames,
        // not kotlinx delays), so waitUntilHistory genuinely waits out the debounce window in
        // real time; its timeout is the safety net, not a formality.
        // Note: prefix compaction means repeated typing into one field within a debounce window
        // may legitimately collapse to fewer history rows - see HistoryCaptureHelper.
        waitUntilHistory(1L, today, listOf("salad"))
        waitUntilHistory(2L, today, listOf("4.88"))
    }
}
