package app.zornslemma.dayfile.ui

import android.os.SystemClock
import androidx.lifecycle.viewModelScope
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.fail
import org.junit.Test

// Pins the teardown half of the HomeViewModel's edit pipeline.
//
// The field notifies us of input through InputTransformation.onTextChanged, which Compose can
// still deliver while the composition is being torn down - clearFocus() collapses the field's
// selection, and that counts as an input transformation even though the text is unchanged. By
// then the ViewModel's scope may already be cancelled and its edit consumer stopped, so
// onUserTyped() must drop the callback rather than report the (entirely expected) closed channel
// as a persistence failure and throw inside Compose's input handling.
//
// This is deliberately not a screen test: nothing needs composing, the whole point is the
// ViewModel's state after its scope is gone.
//
// What this file does NOT pin, deliberately: that the consumer's `finally { userEdits.close() }`
// is what actually closes the channel. Closing the channel is unreachable to observe from here -
// the isActive guard returns before the trySend whose result would reveal it, and forcing
// saveEntry() to throw (the other route into that finally) means killing the process. Without
// the close(), this test would still pass - but the mid-lifetime check() in onUserTyped would
// silently stop being able to detect a dead writer, which is the bug the close() exists to
// fix. If you change either half, re-read that reasoning before trusting a green run here.
class HomeViewModelTeardownTest : BaseHomeScreenTest() {

    private val now = LocalDateTime.of(2026, 8, 1, 12, 0)
    private val today = HomeLogic.logicalDateFor(now, LocalTime.of(4, 0))

    @Test
    fun inputArrivingAfterViewModelTeardownIsDroppedWithoutCrashing() {
        seedCategory(id = 1, name = "Diet", ordering = 0)
        val homeViewModel = buildHomeViewModel { now }

        // Baseline: while the ViewModel is live, an edit reaches the database. Without this the
        // test could pass simply because the pipeline was broken all along.
        homeViewModel.onUserTyped(1L, today, "before")
        awaitEntry(catId = 1L, date = today, expected = "before")

        // Reproduce the teardown state. ViewModel.clear() cancels viewModelScope, and isActive is
        // exactly what onUserTyped() branches on, so cancelling the scope drives the same path a
        // navigation-driven clear would without needing the protected onCleared(). join() then
        // guarantees the consumer coroutine has finished, so its finally block has run and the
        // edit channel is genuinely closed before we make the call under test.
        homeViewModel.viewModelScope.cancel()
        runBlocking { homeViewModel.viewModelScope.coroutineContext[Job]?.join() }

        // Must not throw. If the isActive guard were removed, the closed channel would fail the
        // trySend() and trip the check() - i.e. this assertion is the crash this guard prevents.
        homeViewModel.onUserTyped(1L, today, "after")

        // The dropped edit must not have been written anywhere, and the earlier one must be intact.
        runBlocking { assertEntry(1L, today, "before") }
    }

    private fun awaitEntry(catId: Long, date: LocalDate, expected: String) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        var lastSeen: String? = null
        while (SystemClock.uptimeMillis() < deadline) {
            lastSeen = runBlocking { entryDao.getEntryForCategoryAndDate(catId, date) }?.text
            if (lastSeen == expected) return
            Thread.sleep(20)
        }
        fail("entry for cat $catId never became '$expected'; last saw '$lastSeen'")
    }
}
