package app.zornslemma.dayfile.ui

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.zornslemma.dayfile.data.AppStateRepository
import app.zornslemma.dayfile.data.CategoryDao
import app.zornslemma.dayfile.data.CategoryEntity
import app.zornslemma.dayfile.data.EntryDao
import app.zornslemma.dayfile.data.HistoryDao
import app.zornslemma.dayfile.data.HistoryDatabase
import app.zornslemma.dayfile.data.MainDatabase
import app.zornslemma.dayfile.data.SettingsRepository
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule

// Shared app fixtures for the instrumented suites: the compose rule, in-memory Room databases
// and DAOs, both repositories, and the shared-DataStore reset discipline. Screen-specific
// interaction helpers live in the screen-level bases layered on top of this one
// (BaseHomeScreenTest, BaseHistoryScreenTest, BaseCategoryScreenTest); suites that need the
// fixtures but launch no screen (ResetViewModelTest, SettingsScreenBasicsTest)
// derive directly from here.
abstract class BaseAppTest {

    @get:Rule val composeTestRule = createComposeRule()

    // ViewModels built directly by the test harness (never via a ViewModelStore) are plain
    // locals whose viewModelScope is only cancelled by GC, so it outlives the test method.
    // Their stateIn(Eagerly) pipelines keep running and can re-create Room flows (see
    // HomeViewModel.uiStateFlow) on a later test's DataStore write, long after tearDown()
    // has closed the in-memory database. Track them and cancel their scopes in tearDown()
    // BEFORE closing the databases, so no flow creation can race against close().
    protected val trackedViewModels = mutableListOf<ViewModel>()

    protected fun trackViewModel(viewModel: ViewModel) {
        trackedViewModels.add(viewModel)
    }

    protected lateinit var mainDb: MainDatabase
    protected lateinit var historyDb: HistoryDatabase
    protected lateinit var categoryDao: CategoryDao
    protected lateinit var entryDao: EntryDao
    protected lateinit var historyDao: HistoryDao
    protected lateinit var appStateRepository: AppStateRepository
    protected lateinit var settingsRepository: SettingsRepository

    protected val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun setUp() {
        mainDb =
            Room.inMemoryDatabaseBuilder(context, MainDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        historyDb =
            Room.inMemoryDatabaseBuilder(context, HistoryDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        categoryDao = mainDb.categoryDao()
        entryDao = mainDb.entryDao()
        historyDao = historyDb.historyDao()
        appStateRepository = AppStateRepository(context)
        settingsRepository = SettingsRepository(context)
        // The on-device "settings" DataStore file is shared by every suite in the test process
        // (docs/TESTING_LIMITATIONS_AND_REVIEW.md §8). Tests in this hierarchy write the
        // day-start key, and a leaked non-default value silently reclassifies "current day"
        // for every later test - protection icons appear/vanish and typed edits land on the
        // wrong date - failing tests far away from the culprit. Reset to the shipped default
        // before each test so no test depends on execution order.
        runBlocking { settingsRepository.setDayStartTime(LocalTime.of(4, 0)) }
        // The protection override is persisted in the same shared app-state DataStore file, so
        // it has the identical leak hazard - and being on disk, it outlives not just other tests
        // in this process but whole instrumentation runs. A previous test or run leaving a
        // manual lock would otherwise leak into this test's icon and read-only expectations.
        runBlocking { appStateRepository.setProtectionOverride(null) }
    }

    @After
    fun tearDown() {
        // Cancel every hand-built ViewModel's scope BEFORE closing the databases, and wait for
        // the cancellation to finish. These ViewModels are never stored in a ViewModelStore,
        // so their viewModelScope would otherwise outlive the test. Merely calling cancel() is
        // not enough: a Room suspend DAO operation is a child coroutine and may still be using
        // the database when cancel() returns. Closing mainDb in that interval produces the
        // intermittent "attempt to re-open an already-closed object" failure. cancelAndJoin()
        // waits for the ViewModel's child jobs to finish (or be cancelled) before the database
        // close below.
        runBlocking {
            trackedViewModels.forEach { vm ->
                vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
            }
        }
        trackedViewModels.clear()
        mainDb.close()
        historyDb.close()
        // Same shared-file hazard on the way out: leave the defaults behind for whichever suite
        // or run comes next. (SettingsScreenBasicsTest layers its settings-specific resets on
        // the same principle.)
        runBlocking { settingsRepository.setDayStartTime(LocalTime.of(4, 0)) }
        runBlocking { appStateRepository.setProtectionOverride(null) }
    }

    /**
     * Clicks something that causes a database or DataStore write, and waits until that write
     * becomes observable.
     *
     * Every persistence path in this app travels the same route: the write lands on Room's query
     * executor or DataStore's IO dispatcher, then a flow emits, then combine, then stateIn, then
     * collectAsStateWithLifecycle, then recomposition. `composeTestRule.waitForIdle()` waits for
     * NONE of that - it only quiesces the main thread - so a helper that clicks and then returns
     * hands its caller a screen still showing the previous state. Assertions made against that are
     * either flaky, or - in the `assertDoesNotExist` direction - vacuously green, which is worse
     * because a real regression passes.
     *
     * [read] must report the persisted value. It is deliberately non-suspend: a helper whose value
     * lives behind a suspending DAO or DataStore call wraps it in `runBlocking` at the call site,
     * so whether a read blocks is visible where it is decided and cannot be forgotten.
     *
     * A null from [read] means there is no previous value to compare against (typically the
     * ViewModel has not emitted yet). The wait is then skipped rather than invented, and the click
     * itself fails with a clearer "node not found" than a null comparison would give.
     *
     * The polling loop repeatedly returns to the Compose scheduler, which pumps the main looper, so
     * the Room re-query and the recomposition get their chance to land while we wait; the trailing
     * waitForIdle() closes that out.
     *
     * Not every write fits: where the caller knows the *specific* expected value rather than merely
     * "it changed", assert that directly instead - see confirmDeleteDialog.
     */
    protected fun <T> clickAndAwaitChange(read: () -> T, click: () -> Unit) {
        val before: T? = read()
        click()
        if (before != null) {
            composeTestRule.waitUntil(timeoutMillis = 5_000) { read() != before }
        }
        composeTestRule.waitForIdle()
    }

    protected fun seedCategory(id: Long, name: String, ordering: Int, enabled: Boolean = true) {
        runBlocking {
            categoryDao.insert(
                CategoryEntity(id = id, name = name, ordering = ordering, enabled = enabled)
            )
        }
    }

    protected suspend fun assertEntry(catId: Long, date: LocalDate, expected: String?) {
        val saved = entryDao.getEntryForCategoryAndDate(catId, date)
        if (expected == null) {
            assertNull("Entry written despite protected field for cat $catId", saved)
        } else {
            assertEquals("DB mismatch for cat $catId date $date", expected, saved?.text)
        }
    }

    protected suspend fun assertHistory(catId: Long, date: LocalDate, expected: List<String>) {
        assertEquals(
            "History mismatch for cat $catId date $date",
            expected,
            historyDao.observeHistoryForDateAndCategory(date, catId).first().map { it.text },
        )
    }

    // Compares the ENTIRE entry table against [expected], keyed by (categoryId, date). Deliberate
    // whole-collection equality: on failure the test harness prints both complete maps rather than
    // a message about a single mismatched row, which matters when debugging multi-step journey
    // tests.
    protected suspend fun assertWholeDb(
        expected: Map<Pair<Long, LocalDate>, String>,
        label: String = "whole entry table",
    ) {
        val actual = entryDao.getAllEntries().associate { Pair(it.categoryId, it.date) to it.text }
        assertEquals(label, expected, actual)
    }

    // Polls until the history table contains exactly [expected] texts for the given
    // (date, category). History writes are debounced by HistoryCaptureHelper (a 400ms
    // delay-based debounce running on the real main dispatcher). The Compose test rule's
    // virtual clock advances frame callbacks only - it does NOT advance kotlinx delays - so
    // this really waits out the debounce window in real time inside the poll loop, with the
    // timeout acting as the safety net that absorbs it plus scheduler latency.
    protected fun waitUntilHistory(catId: Long, date: LocalDate, expected: List<String>) {
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking {
                historyDao.observeHistoryForDateAndCategory(date, catId).first().map { it.text } ==
                    expected
            }
        }
    }
}
