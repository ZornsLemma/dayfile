package app.zornslemma.dayfile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

// Installs a StandardTestDispatcher as Dispatchers.Main for the duration of each test, so that
// ViewModel code running in viewModelScope (which is hard-wired to Dispatchers.Main) executes
// deterministically under advanceUntilIdle()/runCurrent() in JVM tests. StandardTestDispatcher
// is used deliberately: the project policy (see docs/TESTING_LIMITATIONS_AND_REVIEW.md) forbids
// combining UnconfinedTestDispatcher with DataStore-backed state, and eager execution is not
// wanted here regardless.
class MainDispatcherRule(val testDispatcher: TestDispatcher = StandardTestDispatcher()) :
    TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(testDispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
