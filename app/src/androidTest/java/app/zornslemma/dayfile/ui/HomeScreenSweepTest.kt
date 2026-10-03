package app.zornslemma.dayfile.ui

import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextReplacement
import androidx.test.filters.LargeTest
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.Test

// A seeded pseudo-random sweep over several days and categories: repeated random navigation,
// unlocking and editing (appending, replacing and clearing), with the whole entry table verified
// against a simple shadow model after every operation and on every navigation arrival.
//
// This is the "extensive" companion to HomeScreenMultiDayJourneyTest: if something breaks, debug
// the hand-written journey first, where the individual steps are easier to reason about; only
// look here if the journey is clean and something still fails. If this test ever catches a
// genuine bug, the healthy response is to promote the failing operation sequence into a new
// hand-written regression case.
//
// Determinism: the PRNG is seeded from a hard-coded constant, so every run performs exactly the
// same sequence of operations. The seed is printed at the start and included in every failure
// message, so any failure can be reproduced and reasoned about offline.
//
// Sensitivity: appends extend whatever the field already shows, so the stored text of an
// append-edited field encodes its entire edit history - a lost write anywhere in the sweep breaks
// an assertion. Replaces and clears add variety and exercise the update/delete paths of
// saveEntry, at the cost of resetting the encoded history for the field involved.
//
// History capture is deliberately not asserted here: entry-table integrity is the focus, and
// history behaviour is covered by HomeScreenSmokeTest and the basics tests.
//
// Slow by design (hundreds of synchronised UI interactions, ~30s on a reasonable emulator):
// tagged @LargeTest so quick runs can exclude it via instrumentation runner arguments such as
// notAnnotation=androidx.test.filters.LargeTest (or
// notClass=app.zornslemma.dayfile.ui.HomeScreenSweepTest).
@LargeTest
class HomeScreenSweepTest : BaseHomeScreenTest() {

    // Fixed clock: noon, day-start 04:00 => logical date == calendar date, deterministic
    // whenever the test runs.
    private val now = LocalDateTime.of(2026, 8, 1, 12, 0)
    private val today = HomeLogic.logicalDateFor(now, LocalTime.of(4, 0))

    @Test
    fun seededSweep_randomEditingPreservesDatabaseIntegrity() {
        val seed = 42L
        val random = Random(seed)
        println("HomeScreenSweepTest: using seed $seed")

        seedCategory(id = 1, name = "Diet", ordering = 0)
        seedCategory(id = 2, name = "Money", ordering = 1)

        launchHomeScreen(buildHomeViewModel { now })

        val offsets = listOf(-2, -1, 0, 1, 2)
        fun dateFor(offset: Int) = today.plusDays(offset.toLong())
        fun offsetLabel(offset: Int) = (if (offset > 0) "+" else "") + offset.toString()

        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        fun randomString(length: Int) =
            (1..length).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")

        // Shadow model of the entry table, keyed by (categoryId, date), updated by each simulated
        // operation. take(MAX_ENTRY_LENGTH) mirrors the UI's silent maxLength truncation so the
        // model remains correct even if accumulated appends ever reach the limit.
        val model = mutableMapOf<Pair<Long, LocalDate>, String>()
        var currentOffset = 0

        fun verify(detail: String) {
            runBlocking { assertWholeDb(model.toMap(), detail) }
        }

        // Protection is a pure function of which day we are on, so assert it at every arrival:
        // non-current days must be locked, the current day must be unlocked. This repeatedly covers
        // the SPEC rules that moving to another day always resets to protected, for free, on every
        // navigation of the sweep.
        //
        // We ALSO verify the whole table at every arrival, not merely after edits: navigation-
        // induced corruption would otherwise remain invisible whenever the next random op
        // happened to clear the affected key. Together with the post-operation verify below,
        // this gives the invariant that after EVERY action - navigation or edit - the database
        // equals the model. (In particular, any clear is always immediately preceded either by
        // an arrival verify or by the previous operation's verify.)
        fun assertArrival(offset: Int, stepDetail: String) {
            if (offset == 0) {
                composeTestRule.onNodeWithContentDescription(protectedDesc).assertDoesNotExist()
            } else {
                composeTestRule.onNodeWithContentDescription(protectedDesc).assertExists()
            }
            verify("$stepDetail day=${offsetLabel(offset)} arrival")
        }

        fun navigateTo(target: Int, stepDetail: String) {
            while (currentOffset < target) {
                goNext()
                currentOffset++
                assertArrival(currentOffset, stepDetail)
            }
            while (currentOffset > target) {
                goPrev()
                currentOffset--
                assertArrival(currentOffset, stepDetail)
            }
        }

        // Navigation always re-protects, but two consecutive operations can land on the same day
        // without navigating, leaving the previous iteration's unlock in force - so check the
        // actual UI state rather than assuming.
        fun ensureUnlocked() {
            val locked =
                composeTestRule
                    .onAllNodesWithContentDescription(protectedDesc)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            if (locked) {
                unlockIfProtected()
            }
        }

        verify("initial load")

        repeat(SWEEP_STEPS) { step ->
            val stepDetail = "seed=$seed step=${step + 1}/$SWEEP_STEPS"

            // Pick and travel to a (possibly unchanged) day, asserting protection and model
            // agreement on the way.
            val target = offsets[random.nextInt(offsets.size)]
            navigateTo(target, stepDetail)
            ensureUnlocked()

            val catId = if (random.nextBoolean()) 1L else 2L
            val key = catId to dateFor(target)
            val opIndex = random.nextInt(3)
            val detail = "$stepDetail op=${OP_NAMES[opIndex]} cat=$catId day=${offsetLabel(target)}"

            when (opIndex) {
                0 -> {
                    // Append: extends whatever the field holds, so the stored text keeps
                    // encoding the field's full edit history.
                    val token = randomString(2 + random.nextInt(4))
                    type(catId, token)
                    model[key] = ((model[key] ?: "") + token).take(maxEntryLength)
                }
                1 -> {
                    // Replace: deterministic whole-field overwrite, independent of cursor
                    // position, exercising saveEntry's update path.
                    val word = randomString(4 + random.nextInt(6))
                    composeTestRule
                        .onNodeWithTag("entry_textfield_$catId")
                        .performTextReplacement(word)
                    composeTestRule.waitForIdle()
                    model[key] = word // generator guarantees non-blank, so the row survives
                }
                else -> {
                    // Clear: exercises saveEntry's delete path (blank text removes the row).
                    composeTestRule.onNodeWithTag("entry_textfield_$catId").performTextClearance()
                    composeTestRule.waitForIdle()
                    model.remove(key)
                }
            }

            // type() waits for its own write, but the replace and clear arms drive the field
            // directly and so have no such wait. Settle the database before comparing, or the
            // model check below races the channel hop in HomeViewModel and reports a divergence
            // that is only a write still in flight.
            awaitEntryPersisted(catId)

            verify(detail)
        }

        verify("final state after $SWEEP_STEPS seeded operations")
    }

    private companion object {
        const val SWEEP_STEPS = 60
        val OP_NAMES = listOf("append", "replace", "clear")
    }
}
