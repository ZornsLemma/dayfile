package app.zornslemma.dayfile.ui

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.viewModelScope
import app.zornslemma.dayfile.data.CategoryRepository
import app.zornslemma.dayfile.data.HistoryCategoryEntity
import app.zornslemma.dayfile.data.HistoryEntryEntity
import java.time.LocalDate
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Before

// History-screen-specific fixtures and interaction helpers, layered on the shared in-memory
// database/repository setup from BaseAppTest. Mirrors the relationship between the home screen
// tests and their base class: broad journey coverage lives in a smoke test class, focused
// single-behaviour checks in a basics class, and anything both need lives here.
abstract class BaseHistoryScreenTest : BaseAppTest() {

    // Fixed date under test. The HistoryViewModel takes its date as a constructor parameter
    // (no clock involved), so no clock injection is needed anywhere in these tests.
    protected val historyDate: LocalDate = LocalDate.of(2026, 8, 1)

    // Production category mutation path (notably deleteCategory's archive-then-delete
    // ordering), for tests simulating out-of-band changes made via the categories screen.
    protected val categoryRepository: CategoryRepository by lazy {
        CategoryRepository(categoryDao, historyDao)
    }

    @Before
    fun resetHistoryFilterState() {
        // The app_state DataStore is a process-wide singleton backed by a real file, so the
        // persisted filter state leaks between test methods and between test classes sharing
        // the instrumentation process. Force the defaults so every test starts clean.
        runBlocking {
            appStateRepository.setHistoryFilterCategory(null)
            appStateRepository.setHistoryFilterIncludeDeleted(false)
        }
    }

    protected fun seedHistoryEntry(
        categoryId: Long,
        text: String,
        savedAt: Long,
        date: LocalDate = historyDate,
    ) {
        runBlocking {
            historyDao.insertHistory(
                HistoryEntryEntity(
                    categoryId = categoryId,
                    date = date,
                    text = text,
                    savedAt = savedAt,
                )
            )
        }
    }

    // Seeds a row in the history database's category archive (history_category), as left behind
    // when a category is deleted. Distinct from seedCategory, which writes the live table.
    protected fun seedArchivedCategory(id: Long, name: String) {
        runBlocking { historyDao.upsertCategory(HistoryCategoryEntity(id = id, name = name)) }
    }

    // Timestamps some minutes in the past: far enough that the rendered relative time is a
    // stable minute-granularity string rather than a racy second-level one, and spaced apart
    // by callers so savedAt (the display sort key) never relies on id tiebreakers. Tests
    // deliberately never assert the relative-time string itself.
    protected fun minutesAgo(minutes: Long): Long = System.currentTimeMillis() - minutes * 60_000L

    protected fun buildHistoryViewModel(date: LocalDate = historyDate): HistoryViewModel =
        HistoryViewModel(
                historyDao = historyDao,
                categoryDao = categoryDao,
                appStateRepository = appStateRepository,
                date = date,
            )
            .also { trackViewModel(it) }

    // The Compose test rule permits only ONE setContent per test method, so a close/reopen
    // cycle cannot be simulated by setting content twice. Instead the ViewModel lives in
    // Compose state: launching sets the content once, and reopenHistoryScreen() swaps in a
    // fresh ViewModel - which is the only thing that actually changes across a real
    // close/reopen, since the ViewModel is nav-scoped.
    private var historyScreenVmState: MutableState<HistoryViewModel>? = null

    protected fun launchHistoryScreen(date: LocalDate = historyDate, onBack: () -> Unit = {}) {
        check(historyScreenVmState == null) {
            "history screen already launched; use reopenHistoryScreen() to close and relaunch it"
        }
        val vmState = mutableStateOf(buildHistoryViewModel(date))
        historyScreenVmState = vmState
        composeTestRule.setContent {
            HistoryScreen(viewModel = vmState.value, date = date, onBack = onBack)
        }
        // Same reasoning as launchHomeScreen's awaitFirstUiState: wait for the ViewModel's first
        // real state, not just for the main thread to go idle. HistoryViewModel.uiStateFlow is a
        // cold flow with no initial value, so first() suspends precisely until there is something
        // to show - which is exactly the gate an immediately-following assertion needs.
        runBlocking { withTimeoutOrNull(5_000) { vmState.value.uiStateFlow.first() } }
        // Then wait for the screen to have DRAWN that state, not just modelled it - see
        // BaseAppTest.awaitRenderedNode. The filter field renders unconditionally at the top of the
        // content column, in the same composition as the entry list, so once it is merged the list
        // is too. Without this, `onNodeWithText("...").assertExists()` straight after launch races
        // the first merge.
        awaitRenderedNode("history_filter_field")
        composeTestRule.waitForIdle()
    }

    // Simulates closing the History screen and reopening it: the previous nav-scoped
    // ViewModel is discarded (its scope cancelled, mirroring onCleared when the back-stack
    // entry pops) and a brand-new one is built over the same repositories, so anything
    // persisted in AppStateRepository must carry the state across.
    protected fun reopenHistoryScreen(date: LocalDate = historyDate) {
        val vmState =
            checkNotNull(historyScreenVmState) {
                "reopenHistoryScreen requires a prior launchHistoryScreen call"
            }
        vmState.value.viewModelScope.cancel()
        vmState.value = buildHistoryViewModel(date)
        composeTestRule.waitForIdle()

        // HistoryViewModel.uiStateFlow is a cold combine with no stateIn, so the brand-new
        // ViewModel has produced nothing until the screen collects it and all four upstream
        // sources (two DataStore, two Room) have emitted. The waitForIdle() above waits for none
        // of that, which would otherwise leave the caller asserting against the seeded initial
        // state. Collecting the same cold flow once here proves every upstream has emitted, and
        // because the screen's own collection started no later, the waitForIdle() after it gives
        // the recomposition its chance to land. Bounded so a genuine upstream failure surfaces as
        // the caller's own assertion failure rather than a hang.
        runBlocking { withTimeoutOrNull(5_000) { vmState.value.uiStateFlow.first() } }
        awaitRenderedNode("history_filter_field")
        composeTestRule.waitForIdle()
    }

    protected fun openFilterDropdown() {
        composeTestRule.onNodeWithTag("history_filter_field").performClick()
        composeTestRule.waitForIdle()
    }

    // Selecting an option closes the dropdown automatically (screen-side onClick handler).
    // Passing null selects the "All" option.
    //
    // Self-synchronising for the reason set out in BaseAppTest.clickAndAwaitChange. The
    // difference here is that the caller knows the exact expected value rather than merely "it
    // changed", so this waits for that value directly.
    protected fun selectFilterOption(categoryId: Long?) {
        openFilterDropdown()
        val tag =
            if (categoryId == null) "history_filter_option_all"
            else "history_filter_option_$categoryId"
        composeTestRule.onNodeWithTag(tag).performClick()
        waitUntilRawFilterIs(categoryId)
        composeTestRule.waitForIdle()
    }

    // Polls until the persisted raw filter equals [expected]. Filter writes travel via
    // viewModelScope onto DataStore's IO dispatcher, so waitForIdle() (which only quiesces the
    // main thread) cannot guarantee a write has landed before a direct DataStore read.
    protected fun waitUntilRawFilterIs(expected: Long?) {
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking { appStateRepository.historyFilterCategory.first() == expected }
        }
    }

    // Asserts the filter field DISPLAYS [value]. Deliberately reads the EditableText semantics
    // rather than using assertTextEquals: a Material3 TextField merges its label ("Category")
    // into the node's Text semantics alongside the displayed value, so the node's combined text
    // content is e.g. ["Category", "Diet"] and can never equal just the value. toString() makes
    // the comparison indifferent to whether the property is stored as a String or an
    // AnnotatedString; both stringify to the plain displayed text.
    //
    // Self-synchronising, because waiting on the persisted filter does NOT mean the field has
    // been recomposed. Those are separate hops: the ViewModel heals the filter and writes it
    // back to DataStore, and only then does the uiState driving this field change. Whenever the
    // renderer is slow enough that the recomposition has not been applied by the time the write
    // lands, waiting on the write returns while the field still shows the previous value - a race,
    // not a failure, which reads as "expected All but was Diet". So wait on the node's own
    // semantics, the thing actually being asserted. Same reasoning as type() in
    // BaseHomeScreenTest, which documents it at length.
    protected fun assertFilterFieldShows(value: String) {
        composeTestRule.waitUntil(timeoutMillis = 5_000) { displayedFilterValue() == value }
        assertEquals(value, displayedFilterValue())
    }

    // The value the filter field is actually displaying right now, read from the node's own
    // semantics rather than from the model, and null while the field is not in the merged tree
    // yet. Uses onAllNodes* so that "not there yet" yields null instead of throwing, which would
    // abort the waitUntil above on its first poll rather than retrying - see type() in
    // BaseHomeScreenTest for the full explanation of why that distinction matters.
    private fun displayedFilterValue(): String? =
        composeTestRule
            .onAllNodesWithTag("history_filter_field")
            .fetchSemanticsNodes()
            .firstOrNull()
            ?.config
            ?.get(SemanticsProperties.EditableText)
            ?.toString()

    // Exactly one toggleable node exists on the history screen (the include-deleted switch),
    // so the semantic-role matcher is unambiguous without a test tag.
    //
    // Self-synchronising like selectFilterOption: reads the persisted flag immediately before the
    // click and waits for it to differ, so the caller does not have to restate the expectation.
    protected fun toggleIncludeDeleted() {
        clickAndAwaitChange(
            read = { runBlocking { appStateRepository.historyFilterIncludeDeleted.first() } },
            click = { composeTestRule.onNode(isToggleable()).performClick() },
        )
    }

    // Vertical position helpers for pinning visual order. Only ever compared between nodes in
    // the SAME window (list items with list items, dropdown items with dropdown items):
    // popup windows have their own coordinate root, so cross-window comparisons are meaningless.
    protected fun yOfTag(tag: String): Float =
        composeTestRule.onNodeWithTag(tag).fetchSemanticsNode().positionInRoot.y

    protected fun yOfText(text: String): Float =
        composeTestRule.onNodeWithText(text).fetchSemanticsNode().positionInRoot.y
}
