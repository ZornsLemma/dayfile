package app.zornslemma.dayfile.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.zornslemma.dayfile.R
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows

// Home-screen-specific fixtures and interaction helpers, layered on the shared app fixtures in
// BaseAppTest (databases, repositories, shared-DataStore resets, seed/assert helpers). Anything
// needed by more than one screen belongs in BaseAppTest; what remains here is meaningful only
// for the home screen: entry-field typing, day navigation and the protection icon.
abstract class BaseHomeScreenTest : BaseAppTest() {

    protected val protectedDesc by lazy {
        context.getString(R.string.protected_content_description)
    }
    protected val unprotectedDesc by lazy {
        context.getString(R.string.unprotected_content_description)
    }

    // The ViewModel behind the currently composed screen, retained so the navigation helpers
    // below can read the state they are about to change. Set by launchHomeScreen.
    private var lastLaunchedHomeViewModel: HomeViewModel? = null

    protected fun buildHomeViewModel(clock: () -> LocalDateTime): HomeViewModel {
        // Prevent cross-test pollution: ensure the durable selected-date store has the date that
        // matches the clock we are about to inject, so HomeViewModel picks the right date (and
        // does not read a stale date left behind by a previous test).
        val initialDate = HomeLogic.logicalDateFor(clock(), LocalTime.of(4, 0))
        runBlocking { appStateRepository.setSelectedDate(initialDate) }

        return HomeViewModel(
                appStateRepository = appStateRepository,
                settingsRepository = settingsRepository,
                entryDao = entryDao,
                categoryDao = categoryDao,
                historyDao = historyDao,
                clock = clock,
            )
            .also { trackViewModel(it) }
    }

    // Navigation callbacks default to no-ops; tests which exercise the overflow menu wiring pass
    // their own recording lambdas.
    protected fun launchHomeScreen(
        homeVm: HomeViewModel,
        onCategories: () -> Unit = {},
        onSettings: () -> Unit = {},
        onHistory: (LocalDate) -> Unit = {},
    ) {
        lastLaunchedHomeViewModel = homeVm
        composeTestRule.setContent {
            HomeScreen(
                homeViewModel = homeVm,
                onCategories = onCategories,
                onSettings = onSettings,
                onHistory = onHistory,
            )
        }
        awaitFirstUiState(homeVm)
        composeTestRule.waitForIdle()
    }

    // Waits for the ViewModel's FIRST real state before any caller is allowed to assert.
    //
    // uiStateFlow starts life as null - HomeViewModel.kt ends the chain with
    // stateIn(viewModelScope, SharingStarted.Eagerly, null) - and only becomes non-null once the
    // date / categories / entries combine has actually produced something. That makes "non-null"
    // an unambiguous gate: until it passes, the screen has no content and there is nothing to find.
    //
    // waitForIdle() on its own does not wait for this. It quiesces the main thread, but the
    // emission arrives from Room and DataStore on their own dispatchers, so the first composition
    // can still be showing an empty screen when it returns. A caller that asserts immediately
    // after launch - `onNodeWithText("Diet").assertExists()`, say - then fails on a screen that was
    // merely early, not wrong. This is the same class of problem type() documents: a fact about
    // the model is not yet a fact about the screen.
    //
    // Phrased as a waitUntil over the flow's value rather than a blocking first() so it matches
    // waitForProtectionState below, and so the clock is pumped between polls - giving the
    // recomposition a chance to land as well as the emission.
    private fun awaitFirstUiState(vm: HomeViewModel) {
        composeTestRule.waitUntil(timeoutMillis = 5_000) { vm.uiStateFlow.value != null }
    }

    // Waits for the ViewModel to report a given protection state.
    //
    // A protection flip caused by a direct DataStore write (the day-start tests) travels through
    // two hops the Compose idling machinery does not track: DataStore -> the combine inside
    // HomeViewModel -> stateIn, and then stateIn -> collectAsStateWithLifecycle -> recomposition.
    // Waiting on the ViewModel's own state pins the first hop deterministically and, importantly,
    // asserts the thing the test is actually named after - that the date was reclassified - rather
    // than inferring it from a field becoming editable. What remains for [type] and
    // [typeExpectRefused] to wait for is then only the recomposition hop, which is short.
    //
    // Prefer this over relying on a later field-level wait to absorb the propagation: those
    // waits have a bounded budget, and asking one to cover two serialised hops as well is what
    // makes them flaky on a loaded emulator.
    //
    // Bounded on purpose, so a genuine failure to propagate reports as a test failure rather
    // than hanging.
    protected fun waitUntilUiProtection(
        viewModel: HomeViewModel,
        isProtected: Boolean,
        timeoutMillis: Long = 5_000,
    ) {
        composeTestRule.waitUntil(timeoutMillis) {
            viewModel.uiStateFlow.value?.isProtected == isProtected
        }
    }

    protected fun type(catId: Long, text: String) {
        // The day's protection can flip on a non-UI path (a direct DataStore write, as the
        // day-start tests do) and reach this field only via DataStore -> combine -> recomposition.
        // The StateFlow value can flip before the recomposition that consumes it has been
        // processed by the compose clock, so waiting on the ViewModel is not enough: wait on the
        // node's own semantics, which is exactly what performTextInput consumes. This makes the
        // helper self-synchronising for every caller, so no test has to hand-roll the wait.
        //
        // Do NOT wrap this predicate in runBlocking. The predicate runs as part of the Compose
        // test, so a nested blocking coroutine can monopolise the main test scheduler and prevent
        // the DataStore/Room continuation whose propagated state this predicate is waiting for
        // from running at all. The direct query lets waitUntil perform the repeated, non-blocking
        // check until the read-only flag is actually removed from the rendered field.
        //
        // The read must also be unable to throw, and that is why it goes through onAllNodes*
        // rather than onNodeWithTag: Compose builds the MERGED semantics tree during
        // measure/layout, a frame or more after the node is composed, so for a moment after
        // launchHomeScreen the field is present in the unmerged tree and absent from the merged
        // one. fetchSemanticsNode() treats that as an error, and waitUntil does NOT catch
        // exceptions from its condition - so an unguarded read aborts the retry loop on the first
        // miss and the test fails having never waited out even a frame. That surfaces as
        // "could not find any node that satisfies (TestTag = ...) ... however, the unmerged tree
        // contains 1 node that matches": a race, not a failure. onAllNodes* yields an empty list
        // instead of throwing, which is the same idiom HomeScreenPinningTest already relies on to
        // wait for a node to disappear.
        composeTestRule.waitUntil(timeoutMillis = 5_000) { isFieldEditable(catId) }
        composeTestRule.onNodeWithTag("entry_textfield_$catId").performTextInput(text)
        composeTestRule.waitForIdle()
        // ...and then for the write to reach Room. Without this, a caller that reads the database
        // straight afterwards can see the pre-edit value while the field already shows the new
        // one, because the edit is still travelling down HomeViewModel's channel. See
        // awaitEntryPersisted.
        awaitEntryPersisted(catId)
    }

    // Not in the tree yet and present-but-empty are both simply "nothing stored yet".
    private fun displayedFieldText(catId: Long): String =
        composeTestRule
            .onAllNodesWithTag("entry_textfield_$catId")
            .fetchSemanticsNodes()
            .firstOrNull()
            ?.config
            ?.get(SemanticsProperties.EditableText)
            ?.toString() ?: ""

    // Waits until Room holds what the field is displaying for [catId] on the selected date.
    //
    // Necessary because an edit does not go straight to the database. onUserTyped pushes it onto a
    // Channel which a single collector coroutine drains onto Room (see HomeViewModel), so between
    // performTextInput returning and the write landing there is a window in which the field has
    // the new text and the database still has the old one. waitForIdle() does not close it - it
    // quiesces the main thread, and none of the channel hop or Room's executor is on it. Asserting
    // straight after a type() therefore observes a race, not a result.
    //
    // Compares the database against the FIELD rather than against an expected value, which is what
    // lets one helper serve append, replacement and clear alike. A blank field is compared as "",
    // matching the fact that clearing deletes the row rather than storing an empty string.
    protected fun awaitEntryPersisted(catId: Long) {
        val date = currentDate()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            val shown = displayedFieldText(catId)
            runBlocking {
                date != null &&
                    entryDao.getEntryForCategoryAndDate(catId, date)?.text.orEmpty() == shown
            }
        }
    }

    // Whether the field for [catId] is currently rendered as editable. Null-tolerant on purpose:
    // "not in the tree yet" and "present but read-only" are both simply "not ready yet".
    private fun isFieldEditable(catId: Long): Boolean =
        composeTestRule
            .onAllNodesWithTag("entry_textfield_$catId")
            .fetchSemanticsNodes()
            .firstOrNull()
            ?.config
            ?.get(SemanticsProperties.IsEditable) == true

    // Waits until the field for [catId] is rendered read-only - the counterpart to the wait
    // inside type(), for tests whose precondition is that typing must be refused.
    //
    // Its real purpose, though, is to make a LATER wait for an editable field meaningful. A test
    // that flips a setting and then waits only for "the field became editable" has not really
    // waited for anything if the field was editable to begin with: the wait matches the state the
    // screen started in and returns at once. Observing the read-only state first means the later
    // wait can only be satisfied by an actual transition. See
    // dayStartChangePromotesSelectedHistoricalDateToUnprotectedToday, which needs this because
    // its starting state is already the unprotected one.
    protected fun waitUntilFieldReadOnly(catId: Long) {
        composeTestRule.waitUntil(timeoutMillis = 5_000) { !isFieldEditable(catId) }
    }

    // The counterpart, for waiting on a day becoming current and its field opening up for edits.
    // Read-only-ness is used in preference to the protection icon because it is derived from the
    // same uiState in the same recomposition, so a field that has become read-only implies the
    // icon is present - but the icon itself sits inside a merging parent and is not as dependable
    // a thing to poll.
    protected fun waitUntilFieldEditable(catId: Long) {
        composeTestRule.waitUntil(timeoutMillis = 5_000) { isFieldEditable(catId) }
    }

    protected fun typeNoIdle(catId: Long, text: String) {
        composeTestRule.onNodeWithTag("entry_textfield_$catId").performTextInput(text)
    }

    // Attempts to type into [catId]'s field, expecting the attempt to be REFUSED because the field
    // is read-only (i.e. the day is protected). Compose's performTextInput asserts that the target
    // node defines the SetText semantics action before doing anything, and read-only fields
    // deliberately do not define it - so the thrown AssertionError is itself the evidence that the
    // UI blocks editing. Callers should still verify the database was left untouched.
    protected fun typeExpectRefused(catId: Long, text: String) {
        assertThrows(
            "Typing into category $catId was not refused; is the field really read-only?",
            AssertionError::class.java,
        ) {
            typeNoIdle(catId, text)
        }
        composeTestRule.waitForIdle()
    }

    // Date navigation and the protection icon are self-synchronising for the reason set out in
    // BaseAppTest.clickAndAwaitChange: each click writes through viewModelScope to DataStore, and
    // the flow -> combine -> stateIn -> recomposition chain that follows is not tracked by the test
    // idling machinery, so waitForIdle() can return with the screen still showing the previous
    // day's protection state.
    //
    // This matters most in the multi-step and sweep suites, which call these helpers in tight
    // loops and then assert on the protection icon. Without the wait, such an assertion can
    // observe the previous day's state.

    protected fun goNext() {
        clickAndAwaitChange(
            read = { currentDate() },
            click = {
                composeTestRule
                    .onNodeWithContentDescription(
                        context.getString(R.string.next_day_content_description)
                    )
                    .performClick()
            },
        )
    }

    protected fun goPrev() {
        clickAndAwaitChange(
            read = { currentDate() },
            click = {
                composeTestRule
                    .onNodeWithContentDescription(
                        context.getString(R.string.previous_day_content_description)
                    )
                    .performClick()
            },
        )
    }

    protected fun unlockIfProtected() {
        clickProtectionIconAndWait(protectedDesc)
    }

    // The mirror of unlockIfProtected(). Kept as a helper rather than left as a raw
    // performClick at the call site because the re-protect step has the same asynchronous
    // DataStore write behind it, and an unwaited raw click there would leave the protection
    // icon assertions reading stale state.
    protected fun reprotectIfUnprotected() {
        clickProtectionIconAndWait(unprotectedDesc)
    }

    private fun clickProtectionIconAndWait(contentDescription: String) {
        clickAndAwaitChange(
            read = { currentIsProtected() },
            click = {
                composeTestRule.onNodeWithContentDescription(contentDescription).performClick()
            },
        )
    }

    // For tests that change the category table out of band, as the categories screen does, and
    // then need to assert on the home screen. The write is synchronous, but the path from it to
    // the screen is Room invalidation -> combine -> stateIn -> recomposition, none of which
    // waitForIdle() tracks. Waiting on the ViewModel's own view of the category pins all of it.
    //
    // This matters in both directions, and one direction is worse than a flake: an
    // assertDoesNotExist() for a category that has just been hidden passes VACUOUSLY if
    // propagation has not happened yet, so an unwaited hide can turn a real bug into a green run.
    protected fun waitUntilUiCategoryEnabled(
        viewModel: HomeViewModel,
        categoryId: Long,
        enabled: Boolean,
    ) {
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            viewModel.uiStateFlow.value?.categories?.firstOrNull { it.id == categoryId }?.enabled ==
                enabled
        }
        composeTestRule.waitForIdle()
    }

    // Nullable on purpose, and the reason is documented on clickAndAwaitChange: if the first
    // emission has not arrived there is no "before" value to compare against, and skipping the
    // wait keeps the failure mode unchanged. The screen renders nothing until the state is
    // non-null, so the click would fail anyway, and "node not found" is a far clearer report than
    // a null state.
    private fun currentDate(): LocalDate? = lastLaunchedHomeViewModel?.uiStateFlow?.value?.date

    private fun currentIsProtected(): Boolean? =
        lastLaunchedHomeViewModel?.uiStateFlow?.value?.isProtected
}
