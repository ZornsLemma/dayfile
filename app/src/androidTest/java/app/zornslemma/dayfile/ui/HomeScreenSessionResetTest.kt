package app.zornslemma.dayfile.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Pins HOME_SCREEN_NOTES.md §7.7: the retained-home session reset must close an open date picker.
//
// MainActivity keeps the home back-stack entry across a long background reset rather than
// rebuilding it, so the HomeScreen instance (and its rememberSaveable state) survives. The date
// picker's in-dialog selection belongs to the previous session, so if it stayed open the user
// could confirm a stale date and quietly undo the reset. MainActivity signals that case by
// incrementing homeSessionResetToken; HomeScreen watches it and closes the picker.
//
// The token is driven directly here rather than through MainActivity's lifecycle observer, so this
// covers the part that is easy to break - the coordination between the token and the composable's
// own state. MainActivity's increment is a single `else` branch and is NOT covered; see the
// UNPINNED note in HOME_SCREEN_NOTES.md §7.7.
class HomeScreenSessionResetTest : BaseHomeScreenTest() {

    private val now = LocalDateTime.of(2026, 8, 1, 12, 0)

    private lateinit var homeViewModel: HomeViewModel

    // Read inside setContent, so writing it from a test body schedules the recomposition that the
    // token handling depends on. A plain local or a captured value would compile but silently
    // never trigger the effect under test.
    private var homeSessionResetToken by mutableIntStateOf(0)

    @Test
    fun retainedHomeSessionResetClosesAnOpenDatePicker() {
        launchHome()

        openDatePicker()
        assertTrue("precondition: the date picker should be open", datePickerIsShowing())

        // A new session's token arrives, i.e. the reset happened while Home stayed composed.
        homeSessionResetToken = 1
        composeTestRule.waitForIdle()

        assertFalse("the stale date picker should have been closed", datePickerIsShowing())
    }

    @Test
    fun pickerSurvivesAnOrdinaryRecomposition() {
        launchHome()

        openDatePicker()
        assertTrue("precondition: the date picker should be open", datePickerIsShowing())

        // Move the date underneath the open picker. The token is untouched, so the picker must
        // survive: only a genuine new session may close it. If it did not, the reset coordination
        // would be far too blunt - any recomposition would dismiss the user's dialog.
        homeViewModel.setSelectedDate(HomeLogic.logicalDateFor(now, LocalTime.of(4, 0)).plusDays(1))
        composeTestRule.waitForIdle()

        assertTrue("an ordinary recomposition must not close the picker", datePickerIsShowing())
    }

    @Test
    fun aFreshScreenWithAnAlreadyUsedTokenStartsWithNoPicker() {
        // The token only ever increments for the retained-home case, so a HomeScreen composed
        // afresh (a child-route reset replaces the back stack) must not be affected by a token
        // value left over from an earlier session. Guards against the token's effect misfiring on
        // first composition.
        homeSessionResetToken = 5
        launchHome()

        assertFalse("no picker should be showing on a fresh screen", datePickerIsShowing())
    }

    private fun launchHome() {
        seedCategory(id = 1, name = "Diet", ordering = 0)
        homeViewModel = buildHomeViewModel { now }
        // Delegates to the shared launcher rather than calling setContent itself. This used to
        // duplicate it, and so silently missed the wait for the ViewModel's first state - leaving
        // the screen blank (HomeScreen renders nothing at all while uiState is null) and every
        // interaction on it failing to find a node. One launcher, so that cannot drift again.
        //
        // The token is passed as a provider so it is read inside setContent: the tests mutate it
        // after launch, which is the point.
        launchHomeScreen(homeViewModel, homeSessionResetToken = { homeSessionResetToken })
    }

    private fun openDatePicker() {
        composeTestRule.onNodeWithTag("home_date_label").performClick()
        // Opening the dialog is a recomposition, and waitForIdle() can return before the dialog
        // has composed. Callers follow this with an explicit "precondition: the date picker should
        // be open" assertion, which would then fail on a screen that was merely early - so wait
        // for the dialog here and leave the caller asserting a settled fact.
        composeTestRule.waitUntil(timeoutMillis = 5_000) { datePickerIsShowing() }
    }

    // The dialog's confirm button is built from android.R.string.ok, so resolving the same
    // platform string is an exact match rather than a guess at wording. Existence is checked
    // across all matches because assertExists() would complain if the string ever matched twice.
    private fun datePickerIsShowing(): Boolean =
        composeTestRule
            .onAllNodesWithText(context.getString(android.R.string.ok))
            .fetchSemanticsNodes()
            .isNotEmpty()
}
