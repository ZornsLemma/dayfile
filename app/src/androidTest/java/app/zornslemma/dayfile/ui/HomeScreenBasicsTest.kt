package app.zornslemma.dayfile.ui

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import app.zornslemma.dayfile.R
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Focused, independent checks of individual home screen behaviours. Each test covers one thing so
// failures localise quickly; HomeScreenSmokeTest then exercises the same behaviours together as a
// single journey, to catch interaction problems between them that isolated tests cannot see.
class HomeScreenBasicsTest : BaseHomeScreenTest() {

    // Fixed clock: noon, day-start 04:00 => logical date == calendar date, deterministic
    // whenever the test runs.
    private val now = LocalDateTime.of(2026, 8, 1, 12, 0)
    private val today = HomeLogic.logicalDateFor(now, LocalTime.of(4, 0))
    private val tomorrow = today.plusDays(1)

    @Test
    fun rendersEnabledCategoriesOnly() {
        seedCategory(id = 1, name = "Diet", ordering = 0)
        seedCategory(id = 2, name = "Money", ordering = 1)
        seedCategory(id = 3, name = "Retired", ordering = 2, enabled = false)

        launchHomeScreen(buildHomeViewModel { now })

        composeTestRule.onNodeWithText("Diet").assertExists()
        composeTestRule.onNodeWithText("Money").assertExists()
        composeTestRule.onNodeWithText("Retired").assertDoesNotExist()
    }

    @Test
    fun disablingCategoryHidesItWithoutDeletingItsEntry() {
        seedCategory(id = 1, name = "Diet", ordering = 0)
        seedCategory(id = 2, name = "Money", ordering = 1)

        val viewModel = buildHomeViewModel { now }

        launchHomeScreen(viewModel)

        type(1L, "salad")

        // Disable a category out-of-band, as the categories screen would. The home screen
        // observes the category table, so the change must be reflected live.
        runBlocking {
            val diet = categoryDao.getCategory(1L)!!
            categoryDao.updateCategories(listOf(diet.copy(enabled = false)))
        }
        // Wait for the hide to actually reach the screen before asserting on it. Without this the
        // assertDoesNotExist() below is a VACUOUS pass whenever propagation has not happened yet
        // - a real regression would go green. (This is the more dangerous half of this test; the
        // re-enable assertions below only produce a visible flake.)
        waitUntilUiCategoryEnabled(viewModel, categoryId = 1L, enabled = false)

        composeTestRule.onNodeWithText("Diet").assertDoesNotExist()
        composeTestRule.onNodeWithText("Money").assertExists()

        // Disabling hides the section but must NOT delete the underlying entry row - only
        // deleting the category itself cascades to its entries.
        runBlocking { assertEntry(1L, today, "salad") }

        // Re-enable: the section returns, reseeded from the database.
        runBlocking {
            val diet = categoryDao.getCategory(1L)!!
            categoryDao.updateCategories(listOf(diet.copy(enabled = true)))
        }
        waitUntilUiCategoryEnabled(viewModel, categoryId = 1L, enabled = true)

        composeTestRule.onNodeWithText("Diet").assertExists()
        composeTestRule.onNodeWithTag("entry_textfield_1").assertTextEquals("salad")
    }

    @Test
    fun typingPersistsImmediately() {
        seedCategory(id = 1, name = "Diet", ordering = 0)

        launchHomeScreen(buildHomeViewModel { now })

        type(1L, "salad")

        runBlocking { assertEntry(1L, today, "salad") }
    }

    @Test
    fun nextDayIsFreshAndProtected() {
        seedCategory(id = 1, name = "Diet", ordering = 0)

        launchHomeScreen(buildHomeViewModel { now })

        goNext()

        composeTestRule.onNodeWithContentDescription(protectedDesc).assertExists()
        composeTestRule.onNodeWithTag("entry_textfield_1").assertTextEquals("")
        runBlocking { assertEntry(1L, tomorrow, null) }
    }

    @Test
    fun returningToPreviousDayRestoresFieldFromDatabase() {
        seedCategory(id = 1, name = "Diet", ordering = 0)

        launchHomeScreen(buildHomeViewModel { now })

        type(1L, "salad")
        goNext()
        goPrev()

        composeTestRule.onNodeWithContentDescription(protectedDesc).assertDoesNotExist()
        composeTestRule.onNodeWithTag("entry_textfield_1").assertTextEquals("salad")
        runBlocking { assertEntry(1L, today, "salad") }
    }

    @Test
    fun repeatedAppendsAccumulateWithinSameDay() {
        seedCategory(id = 1, name = "Diet", ordering = 0)

        launchHomeScreen(buildHomeViewModel { now })

        type(1L, "foo")
        type(1L, "bar")

        composeTestRule.onNodeWithTag("entry_textfield_1").assertTextEquals("foobar")
        runBlocking { assertEntry(1L, today, "foobar") }
    }

    @Test
    fun returningToDayAllowsAppendingToReseededField() {
        seedCategory(id = 1, name = "Diet", ordering = 0)

        launchHomeScreen(buildHomeViewModel { now })

        type(1L, "foo")
        goNext()
        goPrev()

        // A reseeded field's cursor should sit at the end of its text, so the append must
        // extend "foo" rather than replace it. The multi-day journey and (later) sweep tests
        // rely on this behaviour, so it is validated here in isolation first.
        composeTestRule.onNodeWithTag("entry_textfield_1").assertTextEquals("foo")
        type(1L, "bar")

        composeTestRule.onNodeWithTag("entry_textfield_1").assertTextEquals("foobar")
        runBlocking { assertEntry(1L, today, "foobar") }
    }

    @Test
    fun clearingFieldDeletesDatabaseRow() {
        seedCategory(id = 1, name = "Diet", ordering = 0)

        launchHomeScreen(buildHomeViewModel { now })

        type(1L, "temporary")
        runBlocking { assertEntry(1L, today, "temporary") }

        composeTestRule.onNodeWithTag("entry_textfield_1").performTextClearance()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("entry_textfield_1").assertTextEquals("")
        runBlocking { assertEntry(1L, today, null) }
    }

    @Test
    fun entryTextIsTruncatedToMaxLength() {
        seedCategory(id = 1, name = "Diet", ordering = 0)

        launchHomeScreen(buildHomeViewModel { now })

        // Filling the field exactly to the limit must store every character.
        val full = "a".repeat(maxEntryLength)
        type(1L, full)
        runBlocking { assertEntry(1L, today, full) }

        // Typing past the limit must not grow the stored text: the maxLength input
        // transformation truncates (or rejects) whatever would overflow, leaving the original
        // MAX_ENTRY_LENGTH characters intact. The assertion holds under either implementation,
        // but would fail loudly if the framework ever started keeping the tail instead.
        type(1L, "b".repeat(10))
        composeTestRule.onNodeWithTag("entry_textfield_1").assertTextEquals(full)
        runBlocking { assertEntry(1L, today, full) }

        // History must only ever have seen the TRUNCATED text. Snapshots are captured from the
        // same post-transformation callback as persistence, so an un-truncated string turning up
        // in the history table would mean the capture path had been rewired upstream of the
        // maxLength transformation - a regression the entry-table assertions above cannot see.
        //
        // In practice there will be a single history entry (the internal compaction should prevent
        // it) but to avoid depending on this implementation detail in this context where it's not
        // directly relevant, we just make sure all history entries match the truncated text.
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking {
                historyDao.observeHistoryForDateAndCategory(today, 1L).first().isNotEmpty()
            }
        }
        runBlocking {
            val snapshots =
                historyDao.observeHistoryForDateAndCategory(today, 1L).first().map { it.text }
            assertTrue("Expected at least one history snapshot", snapshots.isNotEmpty())
            snapshots.forEach { snapshot ->
                assertEquals(
                    "History recorded text other than the truncated " +
                        "$maxEntryLength-character string",
                    full,
                    snapshot,
                )
            }
        }
    }

    @Test
    fun protectedDayRejectsTyping() {
        seedCategory(id = 1, name = "Diet", ordering = 0)

        launchHomeScreen(buildHomeViewModel { now })

        goNext()
        composeTestRule.onNodeWithContentDescription(protectedDesc).assertExists()

        // The field is read-only while protected, so Compose's performTextInput refuses to run
        // against it (read-only fields do not define the SetText semantics action it requires).
        // The refused attempt is itself the proof that editing is blocked; the assertions below
        // then confirm nothing leaked into the UI or the database regardless.
        typeExpectRefused(1L, "should not land")

        composeTestRule.onNodeWithTag("entry_textfield_1").assertTextEquals("")
        runBlocking { assertEntry(1L, tomorrow, null) }
    }

    @Test
    fun dayCanBeUnlockedEditedAndReprotected() {
        seedCategory(id = 1, name = "Diet", ordering = 0)

        launchHomeScreen(buildHomeViewModel { now })

        goNext()
        composeTestRule.onNodeWithContentDescription(protectedDesc).assertExists()

        unlockIfProtected()
        // The unlocked state has its own icon; asserting it proves we really are unprotected
        // (and gives the otherwise-unused unprotected_content_description a consumer).
        composeTestRule.onNodeWithContentDescription(unprotectedDesc).assertExists()
        composeTestRule.onNodeWithContentDescription(protectedDesc).assertDoesNotExist()

        type(1L, "open for edits")
        runBlocking { assertEntry(1L, tomorrow, "open for edits") }

        // Re-protect by tapping the unlocked icon.
        reprotectIfUnprotected()

        composeTestRule.onNodeWithContentDescription(protectedDesc).assertExists()
        typeExpectRefused(1L, "more")

        // Text typed before re-protecting must have survived, on screen and in the database.
        composeTestRule.onNodeWithTag("entry_textfield_1").assertTextEquals("open for edits")
        runBlocking { assertEntry(1L, tomorrow, "open for edits") }
    }

    @Test
    fun dayStartChangeDemotesSelectedCurrentDateToProtectedHistory() {
        seedCategory(id = 1, name = "Diet", ordering = 0)

        // Noon on the 27th with the shipped 04:00 day-start: the 27th is the current logical
        // day, so no protection icon is shown at all.
        val lateAugustNoon = LocalDateTime.of(2026, 8, 27, 12, 0)
        val viewModel = buildHomeViewModel { lateAugustNoon }
        launchHomeScreen(viewModel)

        // Writing the same DataStore key the Settings screen writes simulates the full
        // Settings -> Home journey without nav wiring. With day-start 23:00, noon on the 27th
        // belongs to the 26th's logical day, so the retained selection must be RECLASSIFIED as
        // historical - without the selection itself being moved.
        //
        // This pins that HomeViewModel consumes dayStartTime reactively (a combine input): a
        // refactor reading it once in init would compile cleanly, pass every other test, and
        // silently leave the classification stale until the 10-minute reset or process death.
        runBlocking { settingsRepository.setDayStartTime(LocalTime.of(23, 0)) }

        // Wait for the reclassification itself before asserting on it. This does need an explicit
        // wait: typeExpectRefused's precondition is that the field has BECOME read-only, so an
        // unpropagated write would leave it editable and fail the test spuriously - the outcome
        // is not the same before and after propagation.
        waitUntilUiProtection(viewModel, isProtected = true)

        // Reclassification must make the field genuinely read-only...
        typeExpectRefused(1L, "should not land")
        // ...and nothing may have been written anywhere.
        runBlocking { assertWholeDb(emptyMap(), "demoted day stays untouched") }
    }

    @Test
    fun dayStartChangePromotesSelectedHistoricalDateToUnprotectedToday() {
        seedCategory(id = 1, name = "Diet", ordering = 0)

        val lateAugustNoon = LocalDateTime.of(2026, 8, 27, 12, 0)
        val viewModel = buildHomeViewModel { lateAugustNoon }
        // Override the durable seed: browse back to yesterday, as the user would.
        runBlocking { appStateRepository.setSelectedDate(LocalDate.parse("2026-08-26")) }
        launchHomeScreen(viewModel)

        // Pin the state this test promotes FROM, before changing anything.
        //
        // buildHomeViewModel seeds selectedDate to this clock's logical date under the default
        // 4:00 day-start - 2026-08-27 - which is also the current logical day, so the field starts
        // out EDITABLE. Only then does the browse-back above make the 26th the selection. That
        // matters because a bare wait for isProtected == false after the day-start change would
        // match that starting value and return immediately, having observed no promotion at all;
        // the failure would then surface as an opaque timeout inside type(). Seeing the field go
        // read-only first means the later wait can only be satisfied by a real transition.
        waitUntilFieldReadOnly(1L)

        // Mirror image of demotion: with day-start 23:00 the 26th IS the current logical day,
        // so the field must become editable automatically.
        runBlocking { settingsRepository.setDayStartTime(LocalTime.of(23, 0)) }

        // The day-start write reaches the field via DataStore -> combine -> stateIn ->
        // recomposition, and NONE of that chain is tracked by the test idling machinery (unlike
        // the input pipeline performClick goes through). Wait for the promotion itself rather
        // than inferring it from a successful type(): a genuine failure to reclassify would
        // otherwise surface as an opaque "condition not satisfied" inside type() rather than as
        // a failed reclassification.
        waitUntilUiProtection(viewModel, isProtected = false)

        // type() still waits on the node's own semantics, which is what actually proves the
        // recomposition landed. That is the only hop left for it to cover.
        type(1L, "promoted note")
        runBlocking {
            assertWholeDb(
                mapOf((1L to LocalDate.parse("2026-08-26")) to "promoted note"),
                "promoted day accepts edits",
            )
        }

        // Leave the durable selected-date store as we found it (this clock's logical date under
        // the default day-start, matching buildHomeViewModel's seeding convention). Belt-and-
        // braces symmetry with the day-start reset in BaseHomeScreenTest: every current consumer
        // re-seeds via buildHomeViewModel anyway, but a future consumer reading the stored date
        // directly must not inherit yesterday's selection from this test.
        runBlocking {
            appStateRepository.setSelectedDate(
                HomeLogic.logicalDateFor(lateAugustNoon, LocalTime.of(4, 0))
            )
        }
    }

    @Test
    fun beforeDayStartEditsLandOnPreviousLogicalDay() {
        seedCategory(id = 1, name = "Diet", ordering = 0)

        // 02:00 with the harness's reset 04:00 day-start: the logical day is still the previous
        // calendar day, and the app should open on it as the current (unprotected) day.
        // Deliberately relies on the base class resetting day-start to the literal 04:00 rather
        // than writing a setting, so this test's meaning is pinned to that literal and stays
        // valid even if the production default ever changes.
        val smallHours = LocalDateTime.of(2026, 8, 1, 2, 0)

        // Pin the expected outcome explicitly rather than trusting logicalDateFor on both sides
        // of the database assertion below: if the function ever misbehaved, the app and the test
        // would otherwise compute the same wrong value and the test would stay green.
        assertEquals(
            LocalDate.parse("2026-07-31"),
            HomeLogic.logicalDateFor(smallHours, LocalTime.of(4, 0)),
        )

        launchHomeScreen(buildHomeViewModel { smallHours })

        composeTestRule.onNodeWithContentDescription(protectedDesc).assertDoesNotExist()

        type(1L, "late night note")

        // Whole-table equality against the literal expected date: proves the row landed on
        // 2026-07-31 AND that nothing leaked onto 2026-08-01 or anywhere else.
        runBlocking {
            assertWholeDb(
                mapOf((1L to LocalDate.parse("2026-07-31")) to "late night note"),
                "day boundary entry table",
            )
        }
    }

    @Test
    fun emptyStateMessageShownWhenNoCategoriesExist() {
        launchHomeScreen(buildHomeViewModel { now })

        composeTestRule
            .onNodeWithText(context.getString(R.string.home_empty_no_categories))
            .assertExists()
    }

    @Test
    fun emptyStateMessageShownWhenAllCategoriesAreDisabled() {
        seedCategory(id = 1, name = "Diet", ordering = 0, enabled = false)

        launchHomeScreen(buildHomeViewModel { now })

        composeTestRule
            .onNodeWithText(context.getString(R.string.home_empty_no_enabled_categories))
            .assertExists()
        composeTestRule.onNodeWithText("Diet").assertDoesNotExist()
    }

    @Test
    fun overflowMenuInvokesNavigationCallbacks() {
        seedCategory(id = 1, name = "Diet", ordering = 0)

        var categoriesClicked = 0
        var settingsClicked = 0
        val historyDates = mutableListOf<LocalDate>()

        launchHomeScreen(
            buildHomeViewModel { now },
            onCategories = { categoriesClicked++ },
            onSettings = { settingsClicked++ },
            onHistory = { historyDates.add(it) },
        )

        val menuIcon = context.getString(R.string.menu_content_description)

        // Each menu entry closes the dropdown, so the menu is reopened before every tap.
        composeTestRule.onNodeWithContentDescription(menuIcon).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(context.getString(R.string.categories_title)).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription(menuIcon).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(context.getString(R.string.history)).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription(menuIcon).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(context.getString(R.string.settings)).performClick()
        composeTestRule.waitForIdle()

        assertEquals(1, categoriesClicked)
        assertEquals(1, settingsClicked)
        // The history entry must hand over the currently selected date.
        assertEquals(listOf(today), historyDates)
    }

    @Test
    fun datePickerNavigatesToPickedDateAndBack() {
        seedCategory(id = 1, name = "Diet", ordering = 0)

        launchHomeScreen(buildHomeViewModel { now })

        type(1L, "salad")

        // Tap the date label to open the picker. The label is two Text nodes (weekday over
        // date), so match the tappable Box by tag - this is also more robust than text
        // matching, since it survives any future reformatting of the label.
        composeTestRule.onNodeWithTag("home_date_label").performClick()
        composeTestRule.waitForIdle()

        // The picker's day cells still expose their full date using the device's FULL format
        // (see below), so keep that formatter here to match them.
        val fullFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)

        // Pick the 15th of the same month: the cell is visible in the grid without paging.
        //
        // Material3 day cells do NOT expose their visible day number to semantics at all, as
        // either text or content description. Each cell instead exposes its full date -
        // formatted for the device locale using the FULL format Material3 uses - as its TEXT
        // semantics. The only content descriptions in the dialog belong to the selected cell,
        // prefixed "Current selection:", and to the month header. We therefore compute the same
        // FULL-format string rather than hard-coding one, so the matcher tracks the device
        // locale. If a future Material version changes this convention, the failure mode is a
        // loud node-not-found here.
        //
        // Note we deliberately never click the cell matching the currently displayed date:
        // its text would be ambiguous with the home screen label behind the dialog. Picking
        // the 15th while on the 1st, then the 1st while on the 15th, avoids that by design.
        composeTestRule.onNodeWithText(today.withDayOfMonth(15).format(fullFormat)).performClick()
        composeTestRule.onNodeWithText(context.getString(android.R.string.ok)).performClick()
        composeTestRule.waitForIdle()

        val picked = today.withDayOfMonth(15)
        composeTestRule.onNodeWithContentDescription(protectedDesc).assertExists()
        composeTestRule.onNodeWithTag("entry_textfield_1").assertTextEquals("")
        runBlocking { assertEntry(1L, picked, null) }

        // Return to today through the picker too: today's cell is in the same month grid,
        // and its text is unambiguous here because the home screen label now shows the 15th.
        // The label is matched by tag for the same reason as above.
        composeTestRule.onNodeWithTag("home_date_label").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(today.format(fullFormat)).performClick()
        composeTestRule.onNodeWithText(context.getString(android.R.string.ok)).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription(protectedDesc).assertDoesNotExist()
        composeTestRule.onNodeWithTag("entry_textfield_1").assertTextEquals("salad")
        runBlocking { assertEntry(1L, today, "salad") }
    }
}
