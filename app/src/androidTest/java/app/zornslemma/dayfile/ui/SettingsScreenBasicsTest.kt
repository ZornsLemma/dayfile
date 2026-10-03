package app.zornslemma.dayfile.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import app.zornslemma.dayfile.R
import app.zornslemma.dayfile.data.HistoryEntryEntity
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

// Focused, independent checks of individual settings-screen behaviours, mirroring the
// conventions of CategoryScreenBasicsTest. Covers the three places where settings owns real,
// breakable logic: the retention dialog's input rules, the clear-history safety gating, and
// the BOM toggle's persistence chain. Everything else on the screen is a thin pass-through
// over already-tested helpers (CsvExportHelperTest, BackupRestoreHelperTest,
// SettingsRepositoryTest) and is deliberately not re-tested here.
//
// Accepted gaps (manual QA): SAF-driven backup/restore/export happy paths, error dialogs,
// the restore-restart flow, snackbar presentation, time-picker round trip, and the dimmed
// (alpha disabledAlpha) rendering of the disabled clear-history row - alpha is invisible to Compose
// semantics (see docs/TESTING_LIMITATIONS_AND_REVIEW.md §7), so inertness is asserted via
// Disabled semantics and dialog-absence instead.
class SettingsScreenBasicsTest : BaseAppTest() {

    private val retentionFieldTag = "settings_retention_days_field"

    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setUpSettingsDefaults() {
        resetSharedDataStore()
    }

    @After
    fun tearDownSettingsDefaults() {
        // Runs before the base tearDown (JUnit runs subclass @After first); the base then
        // restores day-start/protection on the same principle.
        resetSharedDataStore()
    }

    // Day-start and protection-override resets come from BaseAppTest; this covers the
    // settings-specific keys on the same shared-DataStore principle (the file is shared with
    // SettingsRepositoryTest too).
    private fun resetSharedDataStore() {
        runBlocking {
            settingsRepository.setCsvBomEnabled(false)
            settingsRepository.setHistoryRetentionDays(7)
        }
    }

    private fun launchScreen() {
        viewModel =
            SettingsViewModel(context, settingsRepository, mainDb, historyDao).also {
                trackViewModel(it)
            }
        composeTestRule.setContent {
            SettingsScreen(viewModel = viewModel, onBack = {}, onAboutClick = {})
        }
        composeTestRule.waitForIdle()
    }

    // The retention row's headline is a plural; compute the exact rendered string the same way
    // production does rather than hard-coding English text.
    private fun retentionHeadline(days: Int): String =
        context.resources.getQuantityString(R.plurals.settings_history_retention_title, days, days)

    private fun openRetentionDialog() {
        composeTestRule.onNodeWithText(retentionHeadline(7)).performClick()
        composeTestRule.waitForIdle()
    }

    private fun waitUntilRetentionDays(expected: Int) {
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking { settingsRepository.historyRetentionDays.first() == expected }
        }
    }

    private fun waitUntilBom(expected: Boolean) {
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking { settingsRepository.csvBomEnabled.first() == expected }
        }
    }

    private fun waitUntilHistoryCount(expected: Int) {
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking { historyDao.getHistoryCount() == expected }
        }
    }

    // The Settings LazyColumn can render taller than the visible content area: on devices in
    // three-button navigation mode the bottom soft-keys bar eats some of the window, so the
    // "Clear all history" row - which sits near the bottom of the list - falls below the fold
    // and is never composed. It therefore never enters the semantics tree and any interaction
    // on it fails with "could not find any node". Scroll it into view before interacting. The
    // list carries the "settings_list" tag (SettingsScreen.kt) so we swipe on the list
    // specifically rather than the whole root.
    private fun scrollTextIntoView(text: String) {
        val list = composeTestRule.onNodeWithTag("settings_list")
        fun present() =
            try {
                composeTestRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
            } catch (t: Throwable) {
                false
            }
        if (present()) return
        repeat(SCROLL_ATTEMPTS) {
            list.performTouchInput { swipeUp() }
            composeTestRule.waitForIdle()
            if (present()) return
        }
        repeat(SCROLL_ATTEMPTS) {
            list.performTouchInput { swipeDown() }
            composeTestRule.waitForIdle()
            if (present()) return
        }
        // If scrolling never brought it into view, fail with a real message rather than a
        // confusing "could not find any node" from deep inside performClick.
        composeTestRule.onNodeWithText(text).assertExists()
    }

    private companion object {
        private const val SCROLL_ATTEMPTS = 8
    }

    @Test
    fun retentionFieldRejectsNonDigitsAndKeepsOkDisabledWhileBlank() {
        launchScreen()
        openRetentionDialog()

        // The dialog opens pre-filled with the current value; clear it first so rejection is
        // observable as "nothing appears" rather than "the previous value remains".
        composeTestRule.onNodeWithTag(retentionFieldTag).performTextClearance()
        composeTestRule.onNodeWithTag(retentionFieldTag).performTextInput("abc")
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(retentionFieldTag).assertTextEquals("")
        composeTestRule.onNodeWithText(context.getString(android.R.string.ok)).assertIsNotEnabled()
    }

    @Test
    fun retentionFieldCapsInputAtThreeDigits() {
        launchScreen()
        openRetentionDialog()

        composeTestRule.onNodeWithTag(retentionFieldTag).performTextClearance()
        composeTestRule.onNodeWithTag(retentionFieldTag).performTextInput("1234567")
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(retentionFieldTag).assertTextEquals("123")
        composeTestRule.onNodeWithText(context.getString(android.R.string.ok)).assertIsEnabled()

        // Cancelling must discard the edit entirely.
        composeTestRule.onNodeWithText(context.getString(android.R.string.cancel)).performClick()
        composeTestRule.waitForIdle()
        runBlocking { assertEquals(7, settingsRepository.historyRetentionDays.first()) }
    }

    @Test
    fun retentionZeroIsAcceptedExplicitlyAndSwitchesRowSubtitle() {
        launchScreen()
        openRetentionDialog()

        composeTestRule.onNodeWithTag(retentionFieldTag).performTextClearance()
        composeTestRule.onNodeWithTag(retentionFieldTag).performTextInput("0")
        composeTestRule.waitForIdle()

        // 0 parses, so OK must be enabled - no silent coercion back to the default.
        composeTestRule
            .onNodeWithText(context.getString(android.R.string.ok))
            .assertIsEnabled()
            .performClick()
        composeTestRule.waitForIdle()
        waitUntilRetentionDays(0)

        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_history_retention_zero_subtitle))
            .assertExists()
    }

    @Test
    fun clearHistoryRowIsDisabledWhenHistoryIsEmpty() {
        launchScreen()

        composeTestRule.waitUntil(timeoutMillis = 5_000) { viewModel.historyEmpty.value }
        scrollTextIntoView(context.getString(R.string.settings_clear_history_title))

        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_clear_history_title))
            .assertIsNotEnabled()
        // And no confirm dialog may be reachable.
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_clear_history_confirm_message))
            .assertDoesNotExist()
    }

    @Test
    fun clearHistoryConfirmClearsDatabaseAndDisablesRow() {
        runBlocking {
            historyDao.insertHistory(
                HistoryEntryEntity(
                    categoryId = 1,
                    date = LocalDate.parse("2026-01-01"),
                    text = "typo fix",
                    savedAt = 1L,
                )
            )
        }
        launchScreen()

        // The ViewModel's init must observe the seeded row before the row becomes tappable.
        composeTestRule.waitUntil(timeoutMillis = 5_000) { !viewModel.historyEmpty.value }
        scrollTextIntoView(context.getString(R.string.settings_clear_history_title))

        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_clear_history_title))
            .performClick()
        composeTestRule.waitForIdle()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_clear_history_confirm_message))
            .assertExists()

        composeTestRule
            .onNodeWithText(context.getString(R.string.button_clear_history))
            .performClick()
        waitUntilHistoryCount(0)
        composeTestRule.waitForIdle()

        // The row goes inert once there is nothing left to clear.
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_clear_history_title))
            .assertIsNotEnabled()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_clear_history_confirm_message))
            .assertDoesNotExist()
    }

    @Test
    fun bomTogglePersistsBothWays() {
        launchScreen()

        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_bom_supporting_off))
            .assertExists()

        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_bom_title))
            .performClick()
        waitUntilBom(true)
        composeTestRule.waitForIdle()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_bom_supporting_on))
            .assertExists()

        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_bom_title))
            .performClick()
        waitUntilBom(false)
        composeTestRule.waitForIdle()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_bom_supporting_off))
            .assertExists()
    }
}
