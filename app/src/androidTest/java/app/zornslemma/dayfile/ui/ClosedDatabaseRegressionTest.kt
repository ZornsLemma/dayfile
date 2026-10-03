package app.zornslemma.dayfile.ui

import androidx.lifecycle.viewModelScope
import java.time.LocalDateTime
import java.time.LocalTime
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Test

// Regression guard for the "Database is closed" failures introduced by Room 2.8.5's stricter
// throwIfClosed check at flow-creation time (InvalidationTracker.createFlow -> throwIfClosed).
//
// Failure mode being guarded: a test's HomeViewModel is a plain local built by the harness
// (never stored in a ViewModelStore), so its viewModelScope outlives the test method. Its
// uiStateFlow is stateIn(Eagerly) over a chain whose inner hop re-creates a Room flow inside
// flatMapLatest (HomeViewModel.kt:128-139). When a LATER test writes the shared
// app-state/settings DataStore, that emission reaches the still-alive combine, which
// re-creates the Room flow on a database tearDown() has already closed ->
// IllegalStateException: Database is closed.
//
// The fix cancels every tracked ViewModel's scope in tearDown() BEFORE closing the
// databases (BaseAppTest.tearDown). This test pins the invariant that makes that ordering
// safe: with the scope cancelled, a subsequent DataStore write must not re-create the Room
// flow or throw. It reproduces the exact sequence - cancel scope, close DB, write DataStore,
// pump the looper - and asserts no exception surfaces.
class ClosedDatabaseRegressionTest : BaseHomeScreenTest() {

    @Test
    fun dataStoreWriteAfterScopeCancelAndDbCloseDoesNotThrow() {
        seedCategory(id = 1, name = "Diet", ordering = 0)
        val viewModel = buildHomeViewModel { LocalDateTime.of(2026, 8, 1, 12, 0) }
        launchHomeScreen(viewModel)

        // Reproduce the teardown ordering the fix enforces: cancel the scope, THEN close the
        // in-memory database (tearDown() does exactly this for every tracked ViewModel).
        viewModel.viewModelScope.cancel()
        mainDb.close()

        // A later test's setUp writes the shared DataStore. With the scope still alive this
        // re-creates the Room flow on the closed DB and throws "Database is closed"; with the
        // scope cancelled the combine upstream is dead and nothing is re-created.
        runBlocking { settingsRepository.setDayStartTime(LocalTime.of(23, 0)) }

        // Pump the main looper: any exception thrown asynchronously by the pipeline surfaces
        // here rather than silently dying in a coroutine.
        composeTestRule.waitForIdle()
    }
}
