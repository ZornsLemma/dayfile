package app.zornslemma.dayfile.ui

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import app.zornslemma.dayfile.data.AppStateRepository
import app.zornslemma.dayfile.data.HistoryDao
import app.zornslemma.dayfile.data.SettingsRepository
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Date
import kotlinx.coroutines.flow.first

private const val TAG = "ResetViewModel"

/**
 * Builds a [ResetViewModel] from the activity's ViewModelProvider, deriving the [SavedStateHandle]
 * from the provider's creation extras rather than from a constructor argument. This means the
 * caller needs no handle of its own: the framework supplies one tied to the activity's saved-state
 * registry, and the caller constructs the factory with just the shared repositories.
 */
class ResetViewModelFactory(
    private val settingsRepository: SettingsRepository,
    private val historyDao: HistoryDao,
    private val appStateRepository: AppStateRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
        ResetViewModel(
            settingsRepository = settingsRepository,
            historyDao = historyDao,
            appStateRepository = appStateRepository,
            savedStateHandle = extras.createSavedStateHandle(),
        )
            as T

    // Required by the Factory interface for callers that pass no extras; it cannot build a
    // real saved-state handle, so it yields one that never saves. No production caller uses this
    // path - MainActivity supplies a factoryProducer that provides extras.
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        ResetViewModel(
            settingsRepository = settingsRepository,
            historyDao = historyDao,
            appStateRepository = appStateRepository,
            savedStateHandle = SavedStateHandle(),
        )
            as T
}

/**
 * Owns the app-session lifetime of the background timestamp and the 10-minute reset, and nothing
 * else.
 *
 * The background timestamp is **session signal, not durable user data**. That is why it lives in a
 * [SavedStateHandle] rather than in [AppStateRepository] (a DataStore): the handle's lifetime is
 * tied to the *task*, so it is discarded exactly when the user removes the task from the overview
 * screen. DataStore outlives the task, which is the entire source of the inconsistency between
 * "swiped away while foregrounded" and "swiped away while backgrounded" - the former records no
 * timestamp, the latter records one, and DataStore preserved the latter past the swipe.
 *
 * With the handle, the policy is one question, answered by the platform for free:
 * > *When the app comes back, is the task still held?* Task gone -> fresh. Task kept -> 10-minute
 * > rule on time since the last foreground exit.
 *
 * The handle is saved when the process is killed with the task still held (the classic case), and
 * is discarded with the task on swipe-away, so the two swipe cases collapse onto "fresh" with no
 * detection code.
 *
 * Scoped to the **activity**, not to the home back-stack entry: the reset navigation
 * (`popUpTo(startDestination, inclusive=true)`) destroys the home entry and its ViewModel, but the
 * activity's ViewModelStore is untouched by navigation. So this ViewModel survives rotation and
 * survives a successful reset; it dies only with the process. That is the lifetime the timestamp
 * needs.
 *
 * The reset itself - prune, date reset, filter reset - is unchanged from the logic that used to
 * live in DateResetHelper; only the timestamp's container moved.
 */
class ResetViewModel(
    private val settingsRepository: SettingsRepository,
    private val historyDao: HistoryDao,
    private val appStateRepository: AppStateRepository,
    // `internal` rather than `private` only so the instrumented suite can inspect the handle's
    // contents after a resume (that the timestamp was consumed and removed). Nothing outside the
    // ViewModel writes it in production; it is read-only here by convention.
    internal val savedStateHandle: SavedStateHandle,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    /**
     * Records the instant the app leaves the foreground, so the 10-minute grace period can be
     * measured against real elapsed time on return. Persisted in the saved-state bundle, so it
     * survives process death *with the task held* and is discarded when the task is removed.
     */
    suspend fun onMoveToBackground() {
        Log.d(TAG, "onMoveToBackground")
        savedStateHandle[BACKGROUND_TIMESTAMP_KEY] = clock()
    }

    /**
     * Called when the app returns to the foreground. Performs the 10-minute background reset inline
     * (awaited, in order): history pruning, the date reset to the current logical day, and the
     * history filter reset. Returns true when the reset fired - the app was backgrounded for 10
     * minutes or more, so the caller forces a return to the home screen - and false when it was
     * backgrounded for less than the threshold, in which case nothing is touched and the caller
     * must not navigate.
     *
     * The timestamp is cleared on every resume. This is not a debugging convenience: while the app
     * is in the foreground the timestamp is null, so a process death that happens *while
     * foregrounded* (with the task still held) reads null and starts fresh regardless of whether
     * the saved-state bundle happened to survive that particular kill. Clearing makes the
     * foreground-kill case robust rather than dependent on the one lifecycle detail that is least
     * guaranteed.
     */
    suspend fun onReturnToForeground(): Boolean {
        val backgroundTime = savedStateHandle.get<Long?>(BACKGROUND_TIMESTAMP_KEY)
        savedStateHandle.remove<Long?>(BACKGROUND_TIMESTAMP_KEY)

        val now = clock()
        val shouldReset = HomeLogic.shouldReset(backgroundTime, now, AUTO_RESET_TIMEOUT_MS)
        Log.d(
            TAG,
            "onReturnToForeground: backgroundTime=${backgroundTime.toLogDateString()} shouldReset=$shouldReset",
        )
        if (!shouldReset) return false

        // In general, the precise time we prune history doesn't matter for history
        // retention day settings >= 1. By doing it here, we ensure that in pragmatic terms,
        // a 0-day retention setting means "prune on reset".
        pruneHistory(now)

        // Move the selection to the current logical day via the single date-writing funnel,
        // but only when it actually differs: the protection wrinkle the SPEC calls out is
        // preserved - forced onto home wherever the user was, but the current date's
        // protection is retained when the day did not change.
        val today =
            HomeLogic.logicalDateFor(
                LocalDateTime.ofInstant(Instant.ofEpochMilli(now), ZoneId.systemDefault()),
                settingsRepository.dayStartTime.first(),
            )
        if (appStateRepository.selectedDate.first() != today) {
            appStateRepository.setSelectedDate(today)
        }

        appStateRepository.clearHistoryFilter()
        return true
    }

    private suspend fun pruneHistory(now: Long) {
        val retentionDays = settingsRepository.historyRetentionDays.first()
        val cutoff = now - retentionDays * 24L * 60L * 60L * 1000L
        Log.d(TAG, "pruneHistory: retentionDays=$retentionDays cutoff=${cutoff.toLogDateString()}")
        historyDao.pruneOld(cutoff)
    }

    companion object {
        // Internal rather than private so the instrumented suite can seed and inspect the
        // handle directly (it constructs the ViewModel with a SavedStateHandle rather than going
        // through a ViewModelStore, which is what makes the decision logic testable without real
        // process death). The string must stay in sync with the key the ViewModel reads; the
        // test references it by name for that reason.
        internal const val BACKGROUND_TIMESTAMP_KEY = "background_timestamp"

        // The grace period the SPEC asks for: a background of this length or more starts a new
        // session on the home screen with the current logical date.
        const val AUTO_RESET_TIMEOUT_MS = 10L * 60L * 1000L
    }
}

private fun Long?.toLogDateString() =
    "$this ${if (this == null) "" else "(${Date(this).toString()})"}"
