package app.zornslemma.dayfile.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Pins the rotation/background distinction that the home screen's lifecycle observer relies on
// (HOME_SCREEN_NOTES §7.6). The decision is a pure function of isChangingConfigurations(), so it
// is unit-testable on the JVM without composing the screen - the same rationale as
// buildKeyboardOptions being top-level in CategoryKeyboardOptionsTest. The platform call itself
// (Activity.isChangingConfigurations()) is not covered here; only the mapping from it to an
// action, which is what could drift.
class HomeFocusPolicyTest {

    @Test
    fun `clears focus when the app leaves the foreground`() {
        assertTrue(shouldClearFocusOnPause(isChangingConfigurations = false))
    }

    @Test
    fun `keeps focus across a rotation`() {
        // The one case where focus is preserved: the app stays on screen and the user is still
        // typing, so clearing focus here would break the §6 restore path.
        assertFalse(shouldClearFocusOnPause(isChangingConfigurations = true))
    }

    @Test
    fun `the two cases are exact complements`() {
        // Guards against a refactor that invents a third state or inverts the mapping: every
        // input must land in exactly one of the two outcomes.
        assertEquals(false, shouldClearFocusOnPause(true))
        assertEquals(true, shouldClearFocusOnPause(false))
    }
}
