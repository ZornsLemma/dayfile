package app.zornslemma.dayfile.ui

import androidx.compose.ui.unit.dp

// Unicode characters expressed explicitly via variables to make it obvious where they are used
// (rather than relying on us recognising visually that we have "—" rather than "-" in a string
// literal).
const val emDash = "\u2014"
const val bulletPoint = "\u2022"
const val middleDot = "\u00b7"

// MD3 says 12.dp but MyExposedDropdownMenuBox's dropdown item text doesn't line up with the parent
// TextField text with that.
val menuLeftPadding = 16.dp
// Seems best to make the right padding symmetrical.
val menuRightPadding = menuLeftPadding

// MD3 specs say there should be a 24.dp horizontal border, but this seems quite ugly. The left hand
// edge of the dialog's body controls don't line up with the close icon and the right hand edges
// don't line up with the right hand edge of the "Save" text button. Some of the screenshots in the
// documentation seem to show some but not all of these misalignments. It just feels half-baked and
// inconsistent so I'm going with this.
val screenContentHorizontalPadding = 16.dp

val screenContentVerticalPadding = 8.dp

const val maxCategoryNameLength = 32
const val maxEntryLength = 512

// Really not sure what to make of this. I've had extensive, confusing and fruitless discussions
// with various LLMs about this, the actual value, whether it's correct for dark themes or not,
// whether another mechanism should be used to get a disabled appearance when a composable doesn't
// have an actual enabled flag. This will have to do.
const val disabledAlpha = 0.38f
