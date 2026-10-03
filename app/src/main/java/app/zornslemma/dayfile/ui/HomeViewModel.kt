package app.zornslemma.dayfile.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.zornslemma.dayfile.data.AppStateRepository
import app.zornslemma.dayfile.data.CategoryDao
import app.zornslemma.dayfile.data.CategoryEntity
import app.zornslemma.dayfile.data.EntryDao
import app.zornslemma.dayfile.data.EntryEntity
import app.zornslemma.dayfile.data.HistoryDao
import app.zornslemma.dayfile.data.SettingsRepository
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// A persistent source of complexity and problems in the design and implementation here has been
// the trade-off between data durability, performance and code complexity. The current
// implementation deliberately takes an opinionated stance:
//
// - Edits made by the user on the home screen are *immediately* persisted to the entry table. (This
//   may involve insert, update or delete depending on what's happening, which is just a low level
//   orthogonal implementation detail.) There is no de-bouncing here. The Room database is local,
//   the writes are likely to be fast enough, and the data is valuable. If the user types "foo123",
//   we want "foo123" in the database no matter what, not "foo" because the app crashed, or because
//   the user backgrounded it and Android killed it after the "foo" was written and before a
//   debounced "foo123" was written. De-bounce plus SavedStateHandle would potentially guarantee the
//   same durability with improved performance, but without concrete evidence of performance
//   problems we prefer the simpler implementation. There is a window of a few milliseconds when
//   things may be lost before they hit the database, which is acceptable - a 400ms window for
//   debouncing is not.
//
// - In order to allow recovery of valuable data in the event of fumbles and other accidents where
//   the user typed something and then accidentally deletes it, we persist a history of all
//   edits with "maximal changes". This way if the user types "The phone number is <pause> 344222",
//   drops the phone, grabs it and accidentally loses the last three digits, the history *will*
//   contain the full 344222 version, not just "The phone number is" or "The phone number is 344".
//   History writes are de-bounced, partly to reduce writes to the database and also to avoid adding
//   a new history entry after every keystroke - by definition, history records multiple states, not
//   just the latest state. De-bouncing allows us to filter out intermediate states. We do *not*
//   attempt to accommodate the double Murphy situation here where the user fumbles the phone and
//   somehow the app crashes or Android kills it before the debounced history gets written to the
//   database. This would be possible, but the extra complexity is unlikely to be thoroughly tested
//   and adds the risk of subtle bugs that are more harmful than the low probability death of the
//   app with unpersisted history.

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val appStateRepository: AppStateRepository,
    private val settingsRepository: SettingsRepository,
    private val entryDao: EntryDao,
    private val categoryDao: CategoryDao,
    private val historyDao: HistoryDao,
    private val clock: () -> LocalDateTime = LocalDateTime::now,
) : ViewModel() {
    private val historyCaptureHelper = HistoryCaptureHelper(historyDao, viewModelScope)

    private data class UserEdit(val categoryId: Long, val date: LocalDate, val text: String)

    private val userEdits = Channel<UserEdit>(Channel.UNLIMITED)

    init {
        viewModelScope.launch {
            try {
                for (edit in userEdits) {
                    saveEntry(edit)
                }
            } finally {
                // If saveEntry throws, the exception is still allowed to propagate: silently
                // discarding an edit would contradict the policy above. Closing the channel in
                // this finally also ensures that a later trySend() cannot report success when
                // there is no longer a writer to persist what the user typed. The same cleanup is
                // appropriate when this ViewModel is cleared and its scope is cancelled.
                userEdits.close()
            }
        }
    }

    private data class HomeDateState(
        val date: LocalDate,
        val isCurrent: Boolean,
        val isProtected: Boolean,
    )

    data class HomeUiState(
        val date: LocalDate,
        val categories: List<CategoryEntity>,
        val entries: List<EntryEntity>,
        val isCurrent: Boolean,
        val isProtected: Boolean,
    )

    private val dateStateFlow =
        combine(
            appStateRepository.selectedDate,
            settingsRepository.dayStartTime,
            appStateRepository.protectionOverride,
        ) { date, dayStartTime, protectionOverride ->
            val currentDate = HomeLogic.logicalDateFor(clock(), dayStartTime)
            val date =
                if (date == null) {
                    // This is the first run after the app's installation, so set the date to
                    // currentDate. We record the date properly, so that the date shown is recorded
                    // and behaves exactly as if the user navigated there, rather than maybe subtly
                    // floating around as null-with-special-casing until the user interacts with the
                    // app in a way that forces appStateRepository.setSelectedDate() to be called
                    // anyway. This is just an unlikely corner case after first install, but let's
                    // at least try to handle it cleanly. If this causes
                    // appStateRepository.selectedDate to re-emit on first run, we don't really care
                    // much - better just to get this done here without too much complex machinery.
                    appStateRepository.setSelectedDate(currentDate)
                    currentDate
                } else {
                    date
                }
            val isCurrent = (date == currentDate)
            val isProtected = protectionOverride ?: !isCurrent
            HomeDateState(date, isCurrent, isProtected)
        }

    // By combining categories with the other data in HomeUiState, we reduce the chances of an
    // obscure corner case glitch where our list of categories is inconsistent with our entries.
    val uiStateFlow =
        combine(dateStateFlow, categoryDao.observeAllCategories()) { state, categories ->
                state to categories
            }
            .flatMapLatest { (state, categories) ->
                entryDao.observeEntriesForDate(state.date).map { entries ->
                    HomeUiState(
                        date = state.date,
                        categories = categories,
                        entries = entries,
                        isCurrent = state.isCurrent,
                        isProtected = state.isProtected,
                    )
                }
            }
            // Caching the latest state avoids minor jank on the home screen when returning from a
            // child screen. Making it eager doesn't have significant cost and increases the chances
            // our first composition is correct if a child screen does change something relevant.
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun onUserTyped(categoryId: Long, date: LocalDate, text: String) {
        // ViewModel teardown can still deliver a final InputTransformation callback after the
        // ViewModel's scope has been cancelled. In particular, clearFocus() collapses a text
        // field's selection, which invokes this transformation without changing its text. The
        // consumer has already stopped by that point, so this is neither editable live state nor
        // something this cleared ViewModel can persist. Ignore it rather than reporting the
        // expected, closed-channel result as a persistence failure.
        if (!viewModelScope.isActive) {
            return
        }

        // We are not in a suspend context here (this ultimately gets called via an
        // InputTransformation on a TextField) so we can't be updating the database directly. We
        // also don't want to launch our own coroutine here as then each user input gets its own
        // independent coroutine and they could be executed in an arbitrary order. We push the input
        // onto a channel for processing in a single separate coroutine.
        //
        // We do not expect trySend() to fail - the channel has unlimited size and the writer closes
        // it if it unexpectedly stops. However, we'd much rather the app crash if something
        // unexpected is going on than have the user type but have their input silently discarded.
        // (We can't be more defensive than this, because we don't know why this is happening - we
        // really don't expect it to ever happen anyway.)
        check(userEdits.trySend(UserEdit(categoryId, date, text)).isSuccess) {
            "The HomeViewModel's edit channel closed while the ViewModel was still active."
        }
    }

    // Write an entry to the database, making sure that blank entries are represented by simply
    // being absent.
    private suspend fun saveEntry(edit: UserEdit) {
        val categoryId = edit.categoryId
        val date = edit.date
        val text = edit.text
        val existing = entryDao.getEntryForCategoryAndDate(categoryId, date)
        // Unfortunately, moving the cursor within the TextField triggers "updates" to the text
        // even though nothing has changed. Luckily we can filter this out here without any extra
        // real work, since we want to check the existing entry anyway. For the entry table this
        // would merely create additional redundant writes, but for the history table the mostly
        // duplicate entries would create additional records *and* their newer timestamps would
        // be used in history display, losing the effective real timestamp. (Pinned by
        // HomeScreenPinningTest.cursorMovementAloneDoesNotWriteHistoryOrEntries.)
        val existingText = existing?.text ?: ""
        if (text != existingText) {
            if (text.isBlank()) {
                if (existing != null) entryDao.delete(existing)
            } else {
                // Upsert wouldn't really buy us anything here because the primary key ID isn't the
                // natural identity (dateStr, categoryId). Having a surrogate primary key ID is
                // conventional enough that it doesn't feel worth changing how EntryEntity is
                // defined just to use an upsert here.
                if (existing == null) {
                    entryDao.insert(EntryEntity(categoryId = categoryId, date = date, text = text))
                } else {
                    entryDao.update(existing.copy(text = text))
                }
            }
            historyCaptureHelper.offerEntryText(date, categoryId, text)
        }
    }

    fun setSelectedDate(date: LocalDate) {
        // The override drop happens inside AppStateRepository.setSelectedDate; see there.
        viewModelScope.launch { appStateRepository.setSelectedDate(date) }
    }

    fun setProtection(isProtected: Boolean) {
        // The override is persisted state like every other key, so the write goes through the
        // repository; the toolbar icon updates via the resulting flow re-emission.
        viewModelScope.launch { appStateRepository.setProtectionOverride(isProtected) }
    }
}

class HomeViewModelFactory(
    private val appStateRepository: AppStateRepository,
    private val settingsRepository: SettingsRepository,
    private val entryDao: app.zornslemma.dayfile.data.EntryDao,
    private val categoryDao: app.zornslemma.dayfile.data.CategoryDao,
    private val historyDao: app.zornslemma.dayfile.data.HistoryDao,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(HomeViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return HomeViewModel(
                appStateRepository = appStateRepository,
                settingsRepository = settingsRepository,
                entryDao = entryDao,
                categoryDao = categoryDao,
                historyDao = historyDao,
            )
                as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
