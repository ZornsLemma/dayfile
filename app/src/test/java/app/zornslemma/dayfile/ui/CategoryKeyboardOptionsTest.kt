package app.zornslemma.dayfile.ui

import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import app.zornslemma.dayfile.data.CapitalizationMode
import app.zornslemma.dayfile.data.CategoryEntity
import org.junit.Assert.assertEquals
import org.junit.Test

// Pins the per-category keyboard-hint mapping extracted from HomeScreen: how a category's
// stored capitalization mode and autocorrect flag become the KeyboardOptions handed to the
// IME. These options are NOT observable through Compose semantics (they configure the
// platform editor when the field gains focus, and our tests type via synthesized SetText,
// which bypasses the IME entirely), so this mapping is the only automatable half - whether a
// real keyboard honours the hints remains the manual-QA item on ROADMAP. This suite also
// gives CapitalizationMode.toKeyboardCapitalization() its first direct coverage.
//
// Keyboard type and IME action are asserted Unspecified: the fields deliberately inherit
// neutral defaults rather than imposing per-category choices there.
class CategoryKeyboardOptionsTest {

    private fun category(
        capitalization: CapitalizationMode = CapitalizationMode.NONE,
        autoCorrect: Boolean = true,
    ) =
        CategoryEntity(
            id = 1,
            name = "Diet",
            ordering = 0,
            enabled = true,
            autoCorrect = autoCorrect,
            capitalization = capitalization,
        )

    @Test
    fun `each capitalization mode maps to its keyboard counterpart`() {
        // Property-by-property rather than whole-object equality: KeyboardOptions' equals
        // implementation has varied across Compose versions, and per-property assertions are
        // immune to that while giving equally precise failures.
        val expected =
            mapOf(
                CapitalizationMode.NONE to KeyboardCapitalization.None,
                CapitalizationMode.SENTENCES to KeyboardCapitalization.Sentences,
                CapitalizationMode.WORDS to KeyboardCapitalization.Words,
                CapitalizationMode.CHARACTERS to KeyboardCapitalization.Characters,
            )
        expected.forEach { (mode, keyboardCapitalization) ->
            assertEquals(
                "$mode mapped incorrectly",
                keyboardCapitalization,
                buildKeyboardOptions(category(capitalization = mode)).capitalization,
            )
        }
    }

    @Test
    fun `autocorrect flag passes through unchanged`() {
        assertEquals(true, buildKeyboardOptions(category(autoCorrect = true)).autoCorrectEnabled)
        assertEquals(false, buildKeyboardOptions(category(autoCorrect = false)).autoCorrectEnabled)
    }

    @Test
    fun `keyboard type and ime action stay unspecified`() {
        val options = buildKeyboardOptions(category())
        assertEquals(KeyboardType.Unspecified, options.keyboardType)
        assertEquals(ImeAction.Unspecified, options.imeAction)
    }
}
