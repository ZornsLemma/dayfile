package app.zornslemma.dayfile.ui

import androidx.lifecycle.SavedStateHandle
import app.zornslemma.dayfile.data.HistoryEntryEntity
import app.zornslemma.dayfile.data.SettingsRepository
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// Covers the 10-minute background expiry's consequences, driving the real ResetViewModel
// against the real repositories and asserting on stored state (AppStateRepository is final and
// DataStore-backed, so it cannot be faked at JVM level):
//   - both persisted filter keys are cleared,
//   - the date reset to the current logical day fires on every >=10-minute return, carrying the
//     protection override with it (cleared on a genuine day change; untouched when the expiry
//     lands on the same day it left),
//   - expired history rows are pruned.
//
// The ViewModel performs all three consequences itself against the shared repositories, so every
// assertion below is on repository state rather than on callback invocations.
//
// The clock is injected and pinned to a fixed instant (fixedNow below), so the expiry check,
// prune cutoff and logical-day computation all evaluate that one instant, and every expectation
// below is derived from the same constant - no wall-clock reads anywhere, hence no 04:00-boundary
// caveat.
//
// The SavedStateHandle is constructed directly by the test (not via a ViewModelStore), which is
// exactly what makes the *decision logic* testable: a test can seed the handle's timestamp and
// assert the reset fires, without needing real process death. What it cannot test is the handle's
// *storage semantics* - that it survives a background kill and is discarded on swipe-away. That
// dimension is covered by the platform contract the app already depends on for the focus-restore
// gate (restoredOrientation survives process death and dies on swipe-away), and by manual QA of
// the swipe-away case.
//
// onReturnToForeground is a suspend function performing prune -> date reset -> filter reset
// inline and returning whether the reset fired, so the assertions below can drive it directly
// from runBlocking.
//
// No UI is launched: the compose rule is present purely so waitUntil()/waitForIdle() pump the
// main looper on which the ViewModel's launched coroutines run.
class ResetViewModelTest : BaseAppTest() {

    @Before
    fun pinRetentionToShippedDefault() {
        // The prune assertions below implicitly assume the shipped 7-day retention (e.g. the
        // 90-day-old row is "far beyond any plausible cutoff"). Nothing else in this class
        // sets retention, so a suite leaking a different value into the shared settings
        // DataStore would silently move the cutoff and corrupt these assertions in an
        // order-dependent way. Pin the value we assume rather than trusting other suites'
        // reset discipline.
        runBlocking {
            settingsRepository.setHistoryRetentionDays(
                SettingsRepository.DEFAULT_HISTORY_RETENTION_DAYS
            )
        }
    }

    // A fixed stale selection, distinct from any plausible current logical day: the date
    // assertions below key on this value surviving (no reset) or being replaced (reset).
    private val staleDate: LocalDate = LocalDate.parse("2026-01-01")

    // The single instant the ViewModel is pinned to: noon on 2026-08-01, far from the 04:00
    // day-start boundary, so the logical day is unambiguously 2026-08-01.
    private val fixedNow: Long =
        LocalDateTime.of(2026, 8, 1, 12, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    private fun expiredTimestamp(): Long = fixedNow - ResetViewModel.AUTO_RESET_TIMEOUT_MS - 60_000L

    private fun buildViewModel(): ResetViewModel {
        // The MutableMap constructor is the stable, non-deprecated way to build a handle
        // directly in a test (the no-arg one is deprecated and removed in newer lifecycle
        // releases). An empty map means "no saved state", exactly the first-launch condition.
        val handle = SavedStateHandle(HashMap<String, Any?>())
        return ResetViewModel(
                settingsRepository = settingsRepository,
                historyDao = historyDao,
                appStateRepository = appStateRepository,
                savedStateHandle = handle,
                clock = { fixedNow },
            )
            .also { trackViewModel(it) }
    }

    private fun seedExpired(vm: ResetViewModel) {
        vm.savedStateHandle.set(ResetViewModel.BACKGROUND_TIMESTAMP_KEY, expiredTimestamp())
    }

    private fun seedRecent(vm: ResetViewModel) {
        vm.savedStateHandle.set(ResetViewModel.BACKGROUND_TIMESTAMP_KEY, fixedNow - 60_000L)
    }

    @Test
    fun tenMinuteExpiryPerformsFullResetAndReturnsTrue() {
        // The positive case for the new model: a >=10-minute background always starts a new
        // session on the home screen regardless of where the user was, so the date resets to
        // the current logical day AND the manual protection override is dropped with it - the
        // cross-day half of the protection contract. The same-day no-op half is pinned by
        // sameDayExpiryLeavesDateAndOverrideUntouched below.
        val vm = buildViewModel()
        runBlocking {
            appStateRepository.setSelectedDate(staleDate)
            // Seed the override AFTER the date: setSelectedDate clears it as a side effect.
            appStateRepository.setProtectionOverride(true)
            appStateRepository.setHistoryFilterCategory(9L)
            appStateRepository.setHistoryFilterIncludeDeleted(true)
            seedExpired(vm)
        }

        val fired = runBlocking { vm.onReturnToForeground() }

        assertTrue("a >=10-minute background must fire the reset", fired)
        runBlocking {
            // The pinned clock makes this exact: the ViewModel computed "today" from fixedNow,
            // whose logical day (day-start 04:00) is unambiguously 2026-08-01.
            assertEquals(LocalDate.parse("2026-08-01"), appStateRepository.selectedDate.first())
            assertNull(
                "manual override must not survive a genuine day change",
                appStateRepository.protectionOverride.first(),
            )
            assertNull(appStateRepository.historyFilterCategory.first())
            assertEquals(false, appStateRepository.historyFilterIncludeDeleted.first())
        }
    }

    @Test
    fun tenMinuteThresholdIsInclusive() {
        val justBefore = buildViewModel()
        runBlocking {
            justBefore.savedStateHandle.set(
                ResetViewModel.BACKGROUND_TIMESTAMP_KEY,
                fixedNow - ResetViewModel.AUTO_RESET_TIMEOUT_MS + 1L,
            )
        }
        assertFalse(
            "a background one millisecond below the threshold must not fire the reset",
            runBlocking { justBefore.onReturnToForeground() },
        )

        val exactlyAt = buildViewModel()
        runBlocking {
            exactlyAt.savedStateHandle.set(
                ResetViewModel.BACKGROUND_TIMESTAMP_KEY,
                fixedNow - ResetViewModel.AUTO_RESET_TIMEOUT_MS,
            )
        }
        assertTrue(
            "a background of exactly ten minutes must fire the reset",
            runBlocking { exactlyAt.onReturnToForeground() },
        )
    }

    @Test
    fun sameDayExpiryLeavesDateAndOverrideUntouched() {
        // The same-day half of the protection contract: when the stored selection already IS
        // the current logical day, an expired home return changes nothing the user can see -
        // no date write, and therefore no override drop. A manual lock on today survives any
        // absence until the user navigates. The filter keys still clear, providing the
        // deterministic completion barrier.
        val vm = buildViewModel()
        runBlocking {
            // The ViewModel's pinned clock makes the logical day exactly 2026-08-01, so seeding
            // that date is the same-day condition.
            appStateRepository.setSelectedDate(LocalDate.parse("2026-08-01"))
            // Seed the override AFTER the date: setSelectedDate clears it as a side effect.
            appStateRepository.setProtectionOverride(true)
            appStateRepository.setHistoryFilterCategory(9L)
            appStateRepository.setHistoryFilterIncludeDeleted(true)
            seedExpired(vm)
        }

        val fired = runBlocking { vm.onReturnToForeground() }

        assertTrue("a >=10-minute background must fire the reset", fired)
        runBlocking {
            assertEquals(LocalDate.parse("2026-08-01"), appStateRepository.selectedDate.first())
            assertEquals(
                "same-day expiry must not drop a manual lock",
                true,
                appStateRepository.protectionOverride.first(),
            )
            assertNull(appStateRepository.historyFilterCategory.first())
            assertEquals(false, appStateRepository.historyFilterIncludeDeleted.first())
        }
    }

    @Test
    fun tenMinuteExpiryPrunesExpiredHistoryRows() {
        // Uses the shipped default retention (7 days): the old row sits far beyond the cutoff
        // (fixedNow - 7 days), the recent one comfortably inside it. Prune keys on saved_at
        // alone; the date column is irrelevant to pruning but kept consistent for readability.
        // (Retention is pinned to that default in pinRetentionToShippedDefault.)
        val oldSavedAt = fixedNow - 90L * 24 * 60 * 60 * 1000
        val recentSavedAt = fixedNow - 60L * 60 * 1000
        val vm = buildViewModel()
        runBlocking {
            historyDao.insertHistory(
                HistoryEntryEntity(
                    categoryId = 1L,
                    date = staleDate,
                    text = "ancient",
                    savedAt = oldSavedAt,
                )
            )
            historyDao.insertHistory(
                HistoryEntryEntity(
                    categoryId = 1L,
                    date = staleDate,
                    text = "fresh",
                    savedAt = recentSavedAt,
                )
            )
            appStateRepository.setSelectedDate(staleDate)
            appStateRepository.setProtectionOverride(true)
            appStateRepository.setHistoryFilterCategory(9L)
            appStateRepository.setHistoryFilterIncludeDeleted(true)
            seedExpired(vm)
        }

        val fired = runBlocking { vm.onReturnToForeground() }

        assertTrue("a >=10-minute background must fire the reset", fired)

        // Whole-list equality: exactly the recent snapshot survives the cutoff.
        runBlocking {
            assertEquals(
                listOf("fresh"),
                historyDao.observeHistoryForDateAndCategory(staleDate, 1L).first().map { it.text },
            )
            assertEquals(LocalDate.parse("2026-08-01"), appStateRepository.selectedDate.first())
            assertNull(appStateRepository.protectionOverride.first())
        }
    }

    @Test
    fun recentBackgroundReturnsFalseAndLeavesEverythingUntouched() {
        // A row far beyond any plausible retention cutoff: if prune were ever moved outside the
        // shared expiry gate, this row would be deleted and the assertion below would fail.
        // (Prune's NOT firing within the grace period is otherwise only implied by the filter
        // counters, since all three consequences share one gate.)
        val ancientSavedAt = fixedNow - 90L * 24 * 60 * 60 * 1000
        val vm = buildViewModel()
        runBlocking {
            historyDao.insertHistory(
                HistoryEntryEntity(
                    categoryId = 1L,
                    date = staleDate,
                    text = "ancient",
                    savedAt = ancientSavedAt,
                )
            )
            appStateRepository.setSelectedDate(staleDate)
            appStateRepository.setProtectionOverride(true)
            appStateRepository.setHistoryFilterCategory(9L)
            appStateRepository.setHistoryFilterIncludeDeleted(true)
            // One minute before fixedNow: well inside the grace period.
            seedRecent(vm)
        }

        val fired = runBlocking { vm.onReturnToForeground() }

        assertFalse("a sub-10-minute background must not fire the reset", fired)
        runBlocking {
            assertEquals(9L, appStateRepository.historyFilterCategory.first())
            assertEquals(true, appStateRepository.historyFilterIncludeDeleted.first())
            // The ancient row must have survived: prune is gated behind the same expiry check
            // as the resets, not run unconditionally on foreground return.
            assertEquals(
                listOf("ancient"),
                historyDao.observeHistoryForDateAndCategory(staleDate, 1L).first().map { it.text },
            )
            // Short background: BOTH the date and the manual override survive - the preservation
            // half of the protection carry-over contract (the reset half is pinned above).
            assertEquals(staleDate, appStateRepository.selectedDate.first())
            assertEquals(true, appStateRepository.protectionOverride.first())
        }
    }

    @Test
    fun emptyHandleMeansFreshSession() {
        // The SavedStateHandle is empty on first launch (and whenever the task was removed), so
        // the reset fires unconditionally - the same "treat as a fresh session" rule the old
        // DataStore null timestamp encoded, now tied to the task's lifetime rather than to a
        // DataStore key that outlives the task.
        val vm = buildViewModel()
        runBlocking {
            appStateRepository.setSelectedDate(staleDate)
            appStateRepository.setProtectionOverride(true)
            appStateRepository.setHistoryFilterCategory(9L)
            appStateRepository.setHistoryFilterIncludeDeleted(true)
            // No timestamp seeded: the handle is empty, as on first launch or after swipe-away.
        }

        val fired = runBlocking { vm.onReturnToForeground() }

        assertTrue("an empty handle must start a fresh session", fired)
        runBlocking {
            assertEquals(LocalDate.parse("2026-08-01"), appStateRepository.selectedDate.first())
            assertNull(appStateRepository.protectionOverride.first())
        }
    }

    @Test
    fun resumeClearsTheBackgroundTimestamp() {
        // Clearing on resume is what makes a process death that happens *while foregrounded*
        // (task still held) read as fresh regardless of whether the saved-state bundle happened
        // to survive that particular kill. After a resume the handle must hold no timestamp.
        val vm = buildViewModel()
        runBlocking {
            vm.savedStateHandle.set(ResetViewModel.BACKGROUND_TIMESTAMP_KEY, expiredTimestamp())
            vm.onReturnToForeground()
        }
        assertNull(
            "resume must consume and remove the background timestamp",
            vm.savedStateHandle.get<Long?>(ResetViewModel.BACKGROUND_TIMESTAMP_KEY),
        )
    }
}
