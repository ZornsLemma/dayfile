package app.zornslemma.dayfile.ui

import android.os.SystemClock
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.percentOffset
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextRange
import java.time.LocalDateTime
import java.time.LocalTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test

// The "pinning tests" from docs/HOME_SCREEN_NOTES.md §2.3, originally written before the phase-B
// home-screen work, guard the text-field architecture ("TextFieldState is the master; the database
// is a durability sink, not a display driver") while it is edited around.
//
// Test 1 pins that cursor movement alone (a selection change with identical text) is a database
// no-op: the no-op filter in HomeViewModel.saveEntry is what keeps cursor moves out of the
// history table and stops their newer timestamps from hiding the real edit time.
//
// Test 2 pins the database-seeding contract when a field is disposed and recreated. Disabling
// the category disposes the section; re-enabling it composes a fresh field, which must be seeded
// from the database without taking focus. This is a different case from a live UI-state
// re-emission, which Test 3 covers separately using the caret as the observable.
class HomeScreenPinningTest : BaseHomeScreenTest() {

    // Fixed clock: noon, day-start 04:00 => logical date == calendar date, deterministic
    // whenever the test runs (same convention as HomeScreenBasicsTest).
    private val now = LocalDateTime.of(2026, 8, 1, 12, 0)
    private val today = HomeLogic.logicalDateFor(now, LocalTime.of(4, 0))

    @Test
    fun cursorMovementAloneDoesNotWriteHistoryOrEntries() {
        seedCategory(id = 1, name = "Diet", ordering = 0)

        launchHomeScreen(buildHomeViewModel { now })

        type(1L, "ab")
        waitUntilHistory(1L, today, listOf("ab"))

        // Move the caret without changing the text: a tap near the left edge of the field places
        // the caret before "ab", and the double tap additionally selects the word. This is the
        // input family the InputTransformation re-emits on cursor moves (HOME_SCREEN_NOTES §2.2),
        // so without the no-op filter in saveEntry this gesture would append a newer duplicate
        // "ab" history snapshot, and the newer timestamp would hide the real edit time.
        composeTestRule.onNodeWithTag("entry_textfield_1").performTouchInput {
            doubleClick(percentOffset(0.05f, 0.5f))
        }
        composeTestRule.waitForIdle()

        // Wait out a full debounce window (400ms) plus margin: a spurious snapshot offered by the
        // gesture would be written by the debounced history flush within that window of the
        // gesture. The Compose test rule's clock does not advance kotlinx delays, so this waits
        // in real time (same reasoning as BaseHomeScreenTest.waitUntilHistory).
        SystemClock.sleep(HISTORY_DEBOUNCE_SETTLE_MS)

        // Still exactly one history snapshot, and exactly one entry row in the whole table.
        runBlocking {
            assertHistory(1L, today, listOf("ab"))
            assertWholeDb(mapOf((1L to today) to "ab"), "cursor move leaves entry table untouched")
        }
    }

    @Test
    fun disposedAndRecreatedFieldReseedsFromDatabaseWithoutTakingFocus() {
        seedCategory(id = 1, name = "Diet", ordering = 0)

        val homeViewModel = buildHomeViewModel { now }
        launchHomeScreen(homeViewModel)

        type(1L, "foo")
        runBlocking { assertEntry(1L, today, "foo") }

        // Force a categories-flow re-emission out-of-band, the way HomeScreenBasicsTest does.
        // Disabling the section disposes the field's TextFieldState with it; re-enabling composes
        // a fresh state which must be seeded from the DATABASE row (which still holds "foo").
        runBlocking {
            val diet = categoryDao.getCategory(1L)!!
            categoryDao.updateCategories(listOf(diet.copy(enabled = false)))
        }

        // Room invalidation reaches ViewModel and Compose asynchronously, and waitForIdle() alone
        // does not reliably track the final DataStore/Room-to-recomposition hop. Wait for the
        // ViewModel to observe the disabled state before treating the field's absence as proof of
        // disposal. Without this, the absence assertion could race ahead of the update and give
        // a false positive for the test's disposal precondition.
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            homeViewModel.uiStateFlow.value?.categories?.firstOrNull { it.id == 1L }?.enabled ==
                false
        }

        // Wait for the node to disappear before issuing the stronger assertion below, rather than
        // relying on waitForIdle() alone. As in BaseHomeScreenTest.type(), the predicate must NOT
        // be wrapped in runBlocking: a nested blocking coroutine can monopolise the Compose test
        // scheduler and delay the propagation being awaited. The merged tree is the right tree
        // here - the tag is found there by the type() helper and the assertTextEquals calls below.
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTag("entry_textfield_1").fetchSemanticsNodes().isEmpty()
        }
        composeTestRule.onNodeWithTag("entry_textfield_1").assertDoesNotExist()

        runBlocking {
            val diet = categoryDao.getCategory(1L)!!
            categoryDao.updateCategories(listOf(diet.copy(enabled = true)))
        }

        // As above, wait for both sides of the asynchronous re-enable path. Merely waiting for
        // the ViewModel state is not quite enough for the focus assertion: the field can be
        // recreated first and its focus-restoration LaunchedEffect can run in a later Compose
        // pass. Waiting for the node and then draining the Compose scheduler ensures the
        // assertion observes the completed fresh composition, rather than an intermediate frame
        // in which the new field has not been attached yet.
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            homeViewModel.uiStateFlow.value?.categories?.firstOrNull { it.id == 1L }?.enabled ==
                true
        }
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTag("entry_textfield_1").fetchSemanticsNodes().size == 1
        }
        composeTestRule.waitForIdle()

        // The re-composed field must NOT have grabbed focus: before the §6 gate the per-field
        // effect re-grabbed focus on every fresh composition (HOME_SCREEN_NOTES §4.2/§4.3); the
        // gate restricts restoration to genuine configuration changes, which this out-of-band
        // enable/disable cycle is not. Asserted before the further typing below.
        composeTestRule.onNodeWithTag("entry_textfield_1").assertIsNotFocused()

        composeTestRule.onNodeWithTag("entry_textfield_1").assertTextEquals("foo")

        // The reseeded field's cursor sits at the end of its text, so further typing appends
        // rather than replaces - the same append-to-reseeded-field contract the multi-day
        // journey tests rely on.
        type(1L, "bar")
        composeTestRule.onNodeWithTag("entry_textfield_1").assertTextEquals("foobar")
        runBlocking { assertEntry(1L, today, "foobar") }
    }

    @Test
    fun uiStateReemissionDoesNotReseedComposedField() {
        seedCategory(id = 1, name = "Diet", ordering = 0)

        val homeViewModel = buildHomeViewModel { now }
        launchHomeScreen(homeViewModel)

        type(1L, "foo")
        runBlocking { assertEntry(1L, today, "foo") }

        // Unlike Test 2, the field remains composed. Put the caret after "f" and change the
        // category name, which causes the categories flow to re-emit HomeUiState while leaving
        // the entry row unchanged. Waiting for that new state also makes the test independent of
        // how quickly the re-emission reaches the ViewModel.
        composeTestRule.onNodeWithTag("entry_textfield_1").performTextInputSelection(TextRange(1))
        runBlocking {
            val diet = categoryDao.getCategory(1L)!!
            categoryDao.updateCategories(listOf(diet.copy(name = "Diet renamed")))
            homeViewModel.uiStateFlow.first { state ->
                state?.categories?.firstOrNull()?.name == "Diet renamed"
            }
        }
        composeTestRule.waitForIdle()

        // The displayed text was already "foo" in both the field and the database, so checking it
        // alone could not tell a correct re-emission from a re-seed. The caret is the observable:
        // typing must still insert "X" after the initial "f". A regression that re-seeds a live
        // field from the database, or otherwise rebuilds its state, would lose that position.
        composeTestRule.onNodeWithTag("entry_textfield_1").performTextInput("X")

        composeTestRule.onNodeWithTag("entry_textfield_1").assertTextEquals("fXoo")
        runBlocking { assertEntry(1L, today, "fXoo") }
    }

    private companion object {
        // HistoryCaptureHelper's 400ms debounce plus comfortable margin.
        const val HISTORY_DEBOUNCE_SETTLE_MS = 700L
    }
}
