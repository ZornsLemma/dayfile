package app.zornslemma.dayfile.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.zornslemma.dayfile.R
import app.zornslemma.dayfile.data.CapitalizationMode
import app.zornslemma.dayfile.data.CategoryEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

// Focused, independent checks of the category add/edit form. Each test covers one thing so
// failures localise quickly; CategoryAddEditSmokeTest then exercises several of them together
// as a single journey across the list/form boundary. Every test here seeds at least one
// category, as required by launchCategoryAddEditScreen (see its documentation for why).
class CategoryAddEditBasicsTest : BaseCategoryScreenTest() {

    @Test
    fun addModeShowsEmptyFormWithDefaults() {
        seedCategory(id = 1, name = "Money", ordering = 0)

        launchCategoryAddEditScreen(categoryId = null)

        composeTestRule
            .onNodeWithText(context.getString(R.string.add_category_title))
            .assertExists()
        assertNameFieldShows("")
        composeTestRule.onNodeWithTag("category_autocorrect_switch").assertIsOn()
        composeTestRule
            .onNodeWithText(context.getString(R.string.capitalization_none))
            .assertExists()
        // Blank name => Save disabled.
        composeTestRule.onNodeWithText(context.getString(R.string.save)).assertIsNotEnabled()
    }

    @Test
    fun editModePrefillsAllFieldsIncludingNonDefaultHints() {
        runBlocking {
            categoryDao.insert(
                CategoryEntity(
                    id = 1,
                    name = "Diet",
                    ordering = 0,
                    enabled = true,
                    autoCorrect = false,
                    capitalization = CapitalizationMode.WORDS,
                )
            )
        }

        launchCategoryAddEditScreen(categoryId = 1L)

        composeTestRule
            .onNodeWithText(context.getString(R.string.edit_category_title))
            .assertExists()
        assertNameFieldShows("Diet")
        composeTestRule.onNodeWithTag("category_autocorrect_switch").assertIsOff()
        composeTestRule
            .onNodeWithText(context.getString(R.string.capitalization_words))
            .assertExists()
        composeTestRule.onNodeWithText(context.getString(R.string.save)).assertIsEnabled()
    }

    @Test
    fun saveNewPersistsNextOrderingEnabledAndPrimesHistory() {
        seedCategory(id = 1, name = "Money", ordering = 0)

        launchCategoryAddEditScreen(categoryId = null)

        typeCategoryName("Snacks")
        clickSave()

        waitUntilCategoryCount(2)
        waitUntilHistoryArchiveContains("Snacks")
        runBlocking {
            assertWholeCategoryTable(
                listOf(row(1, "Money", 0), row(2, "Snacks", 1)),
                "new category saved",
            )
            val archived = historyDao.observeAllCategories().first()
            assertEquals("Snacks", archived.first { it.id == 2L }.name)
        }
    }

    @Test
    fun saveEditUpdatesFieldsKeepsOrderingEnabledAndSyncsHistoryName() {
        seedCategory(id = 1, name = "Diet", ordering = 0)

        launchCategoryAddEditScreen(categoryId = 1L)

        toggleAutocorrect()
        selectCapitalizationOption(R.string.capitalization_words)
        replaceCategoryName("Food")
        clickSave()

        waitUntilCategoryNamed("Food")
        waitUntilHistoryArchiveContains("Food")
        runBlocking {
            // Ordering and enabled must survive the edit untouched; the hint fields must have
            // been written exactly as selected.
            assertWholeCategoryTable(
                listOf(
                    row(
                        1,
                        "Food",
                        0,
                        autoCorrect = false,
                        capitalization = CapitalizationMode.WORDS,
                    )
                ),
                "edited category saved",
            )
            assertEquals(
                "Food",
                historyDao.observeAllCategories().first().first { it.id == 1L }.name,
            )
        }
    }

    @Test
    fun duplicateNameRejectedCaseInsensitively() {
        seedCategory(id = 1, name = "Diet", ordering = 0)
        seedCategory(id = 2, name = "Money", ordering = 1)
        var backCalled = 0

        launchCategoryAddEditScreen(categoryId = null, onBack = { backCalled++ })

        // Different case from "Diet": the check is deliberately case-insensitive.
        typeCategoryName("diet")
        clickSave()

        composeTestRule
            .onNodeWithText(context.getString(R.string.category_name_error_duplicate))
            .assertExists()
        // Still on the form, nothing navigated, nothing written.
        composeTestRule
            .onNodeWithText(context.getString(R.string.add_category_title))
            .assertExists()
        assertEquals(0, backCalled)
        runBlocking { assertWholeCategoryTable(listOf(row(1, "Diet", 0), row(2, "Money", 1))) }
    }

    @Test
    fun selfNameInEditModeSavesWithoutDuplicateError() {
        seedCategory(id = 1, name = "Diet", ordering = 0)
        var backCalled = 0

        launchCategoryAddEditScreen(categoryId = 1L, onBack = { backCalled++ })

        // Saving with the category's own unchanged name must not trip the duplicate check
        // (the exclusion is it.id != categoryId).
        clickSave()

        composeTestRule
            .onNodeWithText(context.getString(R.string.category_name_error_duplicate))
            .assertDoesNotExist()
        assertEquals(1, backCalled)
        runBlocking { assertWholeCategoryTable(listOf(row(1, "Diet", 0))) }
    }

    @Test
    fun duplicateErrorClearsOnNextKeystroke() {
        seedCategory(id = 1, name = "Diet", ordering = 0)

        launchCategoryAddEditScreen(categoryId = null)

        typeCategoryName("diet")
        clickSave()
        composeTestRule
            .onNodeWithText(context.getString(R.string.category_name_error_duplicate))
            .assertExists()

        typeCategoryName("x")

        composeTestRule
            .onNodeWithText(context.getString(R.string.category_name_error_duplicate))
            .assertDoesNotExist()
    }

    @Test
    fun whitespaceOnlyNameKeepsSaveDisabled() {
        seedCategory(id = 1, name = "Money", ordering = 0)

        launchCategoryAddEditScreen(categoryId = null)

        // isNotBlank (not isEmpty) drives Save's enabled state.
        typeCategoryName("   ")
        composeTestRule.onNodeWithText(context.getString(R.string.save)).assertIsNotEnabled()
    }

    @Test
    fun saveTrimsSurroundingWhitespace() {
        seedCategory(id = 1, name = "Money", ordering = 0)

        launchCategoryAddEditScreen(categoryId = null)

        typeCategoryName("  Snacks  ")
        clickSave()

        waitUntilCategoryNamed("Snacks")
        runBlocking {
            assertWholeCategoryTable(
                listOf(row(1, "Money", 0), row(2, "Snacks", 1)),
                "trimmed name stored",
            )
        }
    }

    @Test
    fun nameFieldEnforcesMaxLength() {
        seedCategory(id = 1, name = "Money", ordering = 0)

        launchCategoryAddEditScreen(categoryId = null)

        // Filling the field exactly to the limit must store every character.
        val full = "a".repeat(maxCategoryNameLength)
        typeCategoryName(full)
        assertNameFieldShows(full)

        // Attempting to type past the limit must not grow the field. Empirically the maxLength
        // input transformation REJECTS an over-capacity commit wholesale rather than truncating
        // it mid-string, so the assertion below holds under either reject or truncate semantics
        // - but would fail loudly if the framework ever started keeping the tail instead. Mirrors
        // HomeScreenBasicsTest.entryTextIsTruncatedToMaxLength, which pins the same contract
        // for the home screen's entry fields.
        typeCategoryName("bbbb")
        assertNameFieldShows(full)

        clickSave()
        waitUntilCategoryNamed(full)
        runBlocking {
            val stored = categoryDao.getAllCategories().first { it.name.startsWith("aaa") }
            assertEquals(maxCategoryNameLength, stored.name.length)
        }
    }

    @Test
    fun backWithCleanFormExitsImmediatelyWithoutDialog() {
        seedCategory(id = 1, name = "Diet", ordering = 0)
        var backCalled = 0

        launchCategoryAddEditScreen(categoryId = 1L, onBack = { backCalled++ })

        clickClose()

        assertEquals(1, backCalled)
        composeTestRule
            .onNodeWithText(context.getString(R.string.discard_changes_title))
            .assertDoesNotExist()
    }

    @Test
    fun whitespaceOnlyEditCountsAsClean() {
        seedCategory(id = 1, name = "Diet", ordering = 0)
        var backCalled = 0

        launchCategoryAddEditScreen(categoryId = 1L, onBack = { backCalled++ })

        // Appending a space leaves the trimmed comparison unchanged, so the form counts as
        // clean and back must exit without the discard dialog.
        typeCategoryName(" ")
        clickClose()

        assertEquals(1, backCalled)
        composeTestRule
            .onNodeWithText(context.getString(R.string.discard_changes_title))
            .assertDoesNotExist()
    }

    @Test
    fun dirtyNameTriggersDiscardDialogAndCancelStays() {
        seedCategory(id = 1, name = "Diet", ordering = 0)
        var backCalled = 0

        launchCategoryAddEditScreen(categoryId = 1L, onBack = { backCalled++ })

        replaceCategoryName("Food")
        clickClose()
        composeTestRule
            .onNodeWithText(context.getString(R.string.discard_changes_title))
            .assertExists()

        // Cancel dismisses the dialog and keeps us on the form.
        composeTestRule.onNodeWithText(context.getString(android.R.string.cancel)).performClick()
        composeTestRule.waitForIdle()

        composeTestRule
            .onNodeWithText(context.getString(R.string.discard_changes_title))
            .assertDoesNotExist()
        composeTestRule
            .onNodeWithText(context.getString(R.string.edit_category_title))
            .assertExists()
        assertEquals(0, backCalled)
    }

    @Test
    fun discardExitsWithoutWriting() {
        seedCategory(id = 1, name = "Diet", ordering = 0)
        var backCalled = 0

        launchCategoryAddEditScreen(categoryId = 1L, onBack = { backCalled++ })

        replaceCategoryName("Food")
        clickClose()
        composeTestRule.onNodeWithText(context.getString(R.string.discard)).performClick()
        composeTestRule.waitForIdle()

        assertEquals(1, backCalled)
        runBlocking { assertWholeCategoryTable(listOf(row(1, "Diet", 0)), "discard wrote nothing") }
    }

    @Test
    fun autocorrectToggleAloneMarksFormDirty() {
        seedCategory(id = 1, name = "Diet", ordering = 0)
        var backCalled = 0

        launchCategoryAddEditScreen(categoryId = 1L, onBack = { backCalled++ })

        toggleAutocorrect()
        clickClose()

        composeTestRule
            .onNodeWithText(context.getString(R.string.discard_changes_title))
            .assertExists()
        assertEquals(0, backCalled)
    }

    @Test
    fun capitalizationChangeAloneMarksFormDirty() {
        seedCategory(id = 1, name = "Diet", ordering = 0)
        var backCalled = 0

        launchCategoryAddEditScreen(categoryId = 1L, onBack = { backCalled++ })

        selectCapitalizationOption(R.string.capitalization_words)
        clickClose()

        composeTestRule
            .onNodeWithText(context.getString(R.string.discard_changes_title))
            .assertExists()
        assertEquals(0, backCalled)
    }

    @Test
    fun keyboardHintChoicesRoundTripOnNewCategory() {
        seedCategory(id = 1, name = "Money", ordering = 0)

        launchCategoryAddEditScreen(categoryId = null)

        typeCategoryName("Snacks")
        toggleAutocorrect()
        selectCapitalizationOption(R.string.capitalization_sentences)
        clickSave()

        waitUntilCategoryNamed("Snacks")
        runBlocking {
            assertWholeCategoryTable(
                listOf(
                    row(1, "Money", 0),
                    row(
                        2,
                        "Snacks",
                        1,
                        autoCorrect = false,
                        capitalization = CapitalizationMode.SENTENCES,
                    ),
                ),
                "keyboard hints persisted",
            )
        }
    }
}
