package app.zornslemma.dayfile.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import app.zornslemma.dayfile.R
import app.zornslemma.dayfile.data.EntryEntity
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

// Focused, independent checks of individual category screen behaviours. Each test covers one
// thing so failures localise quickly; CategoryScreenSmokeTest then exercises several of them
// together as a single journey, to catch interaction problems between them.
//
// Accepted gap: the dimmed (alpha disabledAlpha) rendering of disabled categories is not exposed to
// Compose semantics and is therefore not asserted here - it belongs on the manual QA list.
class CategoryScreenBasicsTest : BaseCategoryScreenTest() {

    @Test
    fun rendersInOrderingOrderWithDisabledSwitchOff() {
        seedCategory(id = 1, name = "Diet", ordering = 0)
        seedCategory(id = 2, name = "Money", ordering = 1)
        seedCategory(id = 3, name = "Retired", ordering = 2, enabled = false)

        launchCategoryScreen()

        assertDisplayedOrder(listOf("Diet", "Money", "Retired"))
        composeTestRule.onNodeWithTag("category_switch_1").assertIsOn()
        composeTestRule.onNodeWithTag("category_switch_2").assertIsOn()
        composeTestRule.onNodeWithTag("category_switch_3").assertIsOff()
    }

    @Test
    fun togglePersistsToDatabaseBothWays() {
        seedCategory(id = 1, name = "Diet", ordering = 0)

        launchCategoryScreen()

        toggleEnabled(1)
        runBlocking { assertWholeCategoryTable(listOf(row(1, "Diet", 0, enabled = false))) }

        toggleEnabled(1)
        runBlocking { assertWholeCategoryTable(listOf(row(1, "Diet", 0, enabled = true))) }
    }

    @Test
    fun moveDownThenMoveUpViaMenuPersistsOrdering() {
        seedCategory(id = 1, name = "Diet", ordering = 0)
        seedCategory(id = 2, name = "Money", ordering = 1)
        seedCategory(id = 3, name = "Retired", ordering = 2)

        launchCategoryScreen()

        openItemMenu(1)
        clickMenuItem(R.string.move_down)
        val afterMoveDown = listOf(row(2, "Money", 0), row(1, "Diet", 1), row(3, "Retired", 2))
        // The reorder write lands on Room's executor, so wait for it before reading the table or
        // starting the next reorder - the second move is computed from the same list and would
        // otherwise interleave with the first write.
        waitUntilCategoryOrder(afterMoveDown)
        assertDisplayedOrder(listOf("Money", "Diet", "Retired"))
        runBlocking { assertWholeCategoryTable(afterMoveDown) }

        openItemMenu(3)
        clickMenuItem(R.string.move_up)
        val afterMoveUp = listOf(row(2, "Money", 0), row(3, "Retired", 1), row(1, "Diet", 2))
        waitUntilCategoryOrder(afterMoveUp)
        assertDisplayedOrder(listOf("Money", "Retired", "Diet"))
        runBlocking { assertWholeCategoryTable(afterMoveUp) }
    }

    @Test
    fun moveUpIsDisabledOnFirstRow() {
        seedCategory(id = 1, name = "Diet", ordering = 0)
        seedCategory(id = 2, name = "Money", ordering = 1)

        launchCategoryScreen()

        openItemMenu(1)
        // Disabled M3 menu items expose Disabled semantics but no click action, so the
        // assertion is semantic rather than a tap-and-see-nothing probe.
        composeTestRule.onNodeWithText(context.getString(R.string.move_up)).assertIsNotEnabled()
        // And the state really is untouched.
        assertDisplayedOrder(listOf("Diet", "Money"))
        runBlocking { assertWholeCategoryTable(listOf(row(1, "Diet", 0), row(2, "Money", 1))) }
    }

    @Test
    fun moveDownIsDisabledOnLastRow() {
        seedCategory(id = 1, name = "Diet", ordering = 0)
        seedCategory(id = 2, name = "Money", ordering = 1)

        launchCategoryScreen()

        openItemMenu(2)
        composeTestRule.onNodeWithText(context.getString(R.string.move_down)).assertIsNotEnabled()
        assertDisplayedOrder(listOf("Diet", "Money"))
        runBlocking { assertWholeCategoryTable(listOf(row(1, "Diet", 0), row(2, "Money", 1))) }
    }

    @Test
    fun deleteIsDisabledWhileCategoryIsEnabled() {
        seedCategory(id = 1, name = "Diet", ordering = 0)

        launchCategoryScreen()

        openItemMenu(1)
        composeTestRule.onNodeWithText(context.getString(R.string.delete)).assertIsNotEnabled()
        // No dialog may have opened.
        composeTestRule
            .onNodeWithText(context.getString(R.string.delete_category_title, "Diet"))
            .assertDoesNotExist()
    }

    @Test
    fun deleteDialogNoEntriesVariantAndCancelKeepsCategory() {
        seedCategory(id = 1, name = "Diet", ordering = 0, enabled = false)

        launchCategoryScreen()

        openItemMenu(1)
        clickMenuItem(R.string.delete)
        composeTestRule.onNodeWithText(expectedNoEntriesMessage()).assertExists()

        cancelDeleteDialog()

        // Cancel must leave everything exactly as it was.
        composeTestRule.onNodeWithText("Diet").assertExists()
        runBlocking { assertWholeCategoryTable(listOf(row(1, "Diet", 0, enabled = false))) }
    }

    @Test
    fun deleteDialogShowsNoDateVariantForASingleEntry() {
        seedCategory(id = 1, name = "Diet", ordering = 0, enabled = false)
        runBlocking {
            // The entry table enforces UNIQUE (category_id, date): one entry per category per
            // day. A category can therefore never hold several entries sharing one date, so
            // the dialog's no-date variant is reachable only via a single entry (with one row,
            // earliestDate == latestDate trivially). Seeding two same-date entries here would
            // violate the constraint.
            entryDao.insert(
                EntryEntity(categoryId = 1, date = LocalDate.parse("2026-08-01"), text = "salad")
            )
        }

        launchCategoryScreen()

        openItemMenu(1)
        clickMenuItem(R.string.delete)
        composeTestRule.onNodeWithText(expectedNoDateMessage(1)).assertExists()
    }

    @Test
    fun deleteDialogShowsRangeVariantWhenEntriesSpanDates() {
        seedCategory(id = 1, name = "Diet", ordering = 0, enabled = false)
        runBlocking {
            entryDao.insert(
                EntryEntity(categoryId = 1, date = LocalDate.parse("2026-07-28"), text = "salad")
            )
            entryDao.insert(
                EntryEntity(categoryId = 1, date = LocalDate.parse("2026-08-03"), text = "toast")
            )
        }

        launchCategoryScreen()

        openItemMenu(1)
        clickMenuItem(R.string.delete)
        composeTestRule
            .onNodeWithText(expectedRangeMessage(2, "2026-07-28", "2026-08-03"))
            .assertExists()
    }

    @Test
    fun confirmDeleteRemovesCategoryArchivesNameAndCascadesEntries() {
        seedCategory(id = 1, name = "Diet", ordering = 0, enabled = false)
        runBlocking {
            entryDao.insert(
                EntryEntity(categoryId = 1, date = LocalDate.parse("2026-07-28"), text = "salad")
            )
            entryDao.insert(
                EntryEntity(categoryId = 1, date = LocalDate.parse("2026-08-03"), text = "toast")
            )
        }

        launchCategoryScreen()

        openItemMenu(1)
        clickMenuItem(R.string.delete)
        confirmDeleteDialog()

        // The delete itself runs in a viewModelScope.launch and its write happens on Room's
        // executor, so waitForIdle() inside confirmDeleteDialog() only quiesces the main thread -
        // it does not wait for the row to disappear. Poll for the observable fact first, exactly
        // as the base class's wait helpers are documented to be used, then make the stronger
        // assertions. Without this the UI assertion below races the delete and intermittently
        // finds the row still on screen.
        waitUntilCategoryCount(0)
        waitUntilHistoryArchiveContains("Diet")

        composeTestRule.onNodeWithText("Diet").assertDoesNotExist()
        runBlocking {
            assertWholeCategoryTable(emptyList(), "category removed")
            // Deleting the category cascades to its entries (FK CASCADE) - data loss must be
            // exactly what the dialog promised, no more and no less.
            assertWholeDb(emptyMap(), "entries cascaded with category")
            // The name must be archived in the history database so historical entries remain
            // attributable after deletion.
            val archived = historyDao.observeAllCategories().first()
            assertEquals("Diet", archived.first { it.id == 1L }.name)
        }
    }

    @Test
    fun fabBackAndEditCallbacksFire() {
        seedCategory(id = 1, name = "Diet", ordering = 0)

        var backClicked = 0
        var addClicked = 0
        val editedIds = mutableListOf<Long>()

        launchCategoryScreen(
            onBack = { backClicked++ },
            onAddCategory = { addClicked++ },
            onEditCategory = { editedIds.add(it) },
        )

        composeTestRule
            .onNodeWithContentDescription(
                context.getString(R.string.add_category_content_description)
            )
            .performClick()
        composeTestRule.waitForIdle()

        openItemMenu(1)
        clickMenuItem(R.string.edit)

        composeTestRule
            .onNodeWithContentDescription(context.getString(R.string.back_content_description))
            .performClick()
        composeTestRule.waitForIdle()

        assertEquals(1, addClicked)
        assertEquals(listOf(1L), editedIds)
        assertEquals(1, backClicked)
    }

    @Test
    fun dragReorderPersistsOnRelease() {
        seedCategory(id = 1, name = "Alpha", ordering = 0)
        seedCategory(id = 2, name = "Beta", ordering = 1)
        seedCategory(id = 3, name = "Gamma", ordering = 2)

        launchCategoryScreen()

        // The ONLY automated coverage of the drag wiring chain (gesture -> local list mutation
        // + viewModel.reorder -> onDragStopped -> commitOrder -> single DB write). The drag
        // distance spans two rows, measured from the rendered handles so the gesture adapts to
        // density and font scale instead of hard-coding pixels, with a small overshoot to clear
        // the swap threshold. This is also the most timing-sensitive assertion in the suite: the
        // JVM contract tests pin the ViewModel half of the chain, and the residual gap (two
        // lambda wirings in the screen) falls back to manual QA.
        val dragDistance =
            (yOfTag("category_drag_handle_3") - yOfTag("category_drag_handle_1")) * 1.1f

        composeTestRule.onNodeWithTag("category_drag_handle_1").performTouchInput {
            down(center)
            repeat(30) { moveBy(Offset(0f, dragDistance / 30f)) }
            up()
        }
        composeTestRule.waitForIdle()

        val afterDrag = listOf(row(2, "Beta", 0), row(3, "Gamma", 1), row(1, "Alpha", 2))
        // The commitOrder write lands on Room's executor; the positional assertion above reads the
        // screen's own synchronously-updated state, but the table read must wait for it.
        waitUntilCategoryOrder(afterDrag)
        assertDisplayedOrder(listOf("Beta", "Gamma", "Alpha"))
        runBlocking { assertWholeCategoryTable(afterDrag) }
    }
}
