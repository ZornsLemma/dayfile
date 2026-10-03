package app.zornslemma.dayfile.data

import androidx.compose.ui.text.input.KeyboardCapitalization

enum class CapitalizationMode {
    NONE,
    SENTENCES,
    WORDS,
    CHARACTERS;

    fun toKeyboardCapitalization(): KeyboardCapitalization =
        when (this) {
            NONE -> KeyboardCapitalization.None
            SENTENCES -> KeyboardCapitalization.Sentences
            WORDS -> KeyboardCapitalization.Words
            CHARACTERS -> KeyboardCapitalization.Characters
        }

    @androidx.annotation.StringRes
    fun labelRes(): Int =
        when (this) {
            NONE -> app.zornslemma.dayfile.R.string.capitalization_none
            SENTENCES -> app.zornslemma.dayfile.R.string.capitalization_sentences
            WORDS -> app.zornslemma.dayfile.R.string.capitalization_words
            CHARACTERS -> app.zornslemma.dayfile.R.string.capitalization_characters
        }
}
