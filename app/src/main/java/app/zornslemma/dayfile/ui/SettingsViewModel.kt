package app.zornslemma.dayfile.ui

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.zornslemma.dayfile.R
import app.zornslemma.dayfile.data.BackupRestoreHelper
import app.zornslemma.dayfile.data.CategoryEntity
import app.zornslemma.dayfile.data.CsvExportHelper
import app.zornslemma.dayfile.data.EntryEntity
import app.zornslemma.dayfile.data.HistoryDao
import app.zornslemma.dayfile.data.MainDatabase
import app.zornslemma.dayfile.data.RestoreClosedDatabaseException
import app.zornslemma.dayfile.data.SettingsRepository
import app.zornslemma.dayfile.data.UserVisibleException
import java.io.IOException
import java.time.LocalTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface RestoreState {
    object Idle : RestoreState

    object InProgress : RestoreState

    /**
     * The process must restart before the app is usable again.
     *
     * [restoredDataApplied] distinguishes the two reasons we end up here, because the user's data
     * is in a different state in each and the dialog must not lie about it:
     * - `true`: the replacement succeeded and the restart exists only so Room opens the new file
     *   cleanly.
     * - `false`: the replacement FAILED, but only after `db.close()` had already run. The old live
     *   database is intact so no data is lost, but this process now holds a closed Room graph, so
     *   continuing would crash on the next database-backed screen. See
     *   [app.zornslemma.dayfile.data.RestoreClosedDatabaseException].
     */
    data class RestartRequired(val restoredDataApplied: Boolean) : RestoreState

    data class Error(val messageResId: Int, val formatArgs: List<Any> = emptyList()) : RestoreState
}

sealed interface FileOpState {
    object Idle : FileOpState

    data class Success(@StringRes val messageResId: Int) : FileOpState

    data class Error(
        @StringRes val titleResId: Int,
        @StringRes val messageResId: Int,
        val formatArgs: List<Any> = emptyList(),
    ) : FileOpState
}

/**
 * Dispatcher convention in this class: plain viewModelScope.launch is correct for suspend APIs
 * (Room suspend DAO functions and DataStore suspend calls dispatch to their own executors
 * internally, so Dispatchers.IO would be redundant), while Dispatchers.IO is used only where the
 * body performs blocking, non-suspending I/O (contentResolver streams, SQLite execSQL, file copies)
 * that would otherwise run on the main dispatcher.
 */
class SettingsViewModel(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val database: MainDatabase,
    private val historyDao: HistoryDao,
) : ViewModel() {
    // Expose as StateFlow so the UI can use collectAsStateWithLifecycle() without supplying
    // an initial value, consistent with the other ViewModels in this project.
    val dayStartTime: StateFlow<LocalTime> =
        settingsRepository.dayStartTime.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = SettingsRepository.DEFAULT_DAY_START,
        )

    val csvBomEnabled: StateFlow<Boolean> =
        settingsRepository.csvBomEnabled.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = SettingsRepository.DEFAULT_CSV_BOM,
        )

    val historyRetentionDays: StateFlow<Int> =
        settingsRepository.historyRetentionDays.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = SettingsRepository.DEFAULT_HISTORY_RETENTION_DAYS,
        )

    // Tracks whether the history database has any rows, so the "Clear all history"
    // Settings item can be disabled when there is nothing to clear.
    private val _historyEmpty = MutableStateFlow(true)
    val historyEmpty: StateFlow<Boolean> = _historyEmpty

    // One-shot outcomes of async work owned by this ViewModel, exposed as state and explicitly
    // consumed by the UI. They deliberately live here rather than in remember/rememberSaveable
    // composable state: plain remember would not even survive rotation, and rememberSaveable
    // would be actively wrong — on process death the work dies with the process, but a saved
    // "in progress" flag would revive into a non-dismissable dialog with no work behind it,
    // and a saved "restart required" would claim a restore that possibly never completed.
    // ViewModel state dies with the process, so the fresh composition correctly starts from
    // Idle and the user can simply retry. The owner of the work must own its outcome.
    private val _restoreState = MutableStateFlow<RestoreState>(RestoreState.Idle)
    val restoreState: StateFlow<RestoreState> = _restoreState

    private val _fileOpState = MutableStateFlow<FileOpState>(FileOpState.Idle)
    val fileOpState: StateFlow<FileOpState> = _fileOpState

    init {
        viewModelScope.launch { _historyEmpty.value = historyDao.getHistoryCount() == 0 }
    }

    fun updateDayStartTime(time: LocalTime) {
        viewModelScope.launch { settingsRepository.setDayStartTime(time) }
    }

    fun setCsvBomEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setCsvBomEnabled(enabled) }
    }

    fun updateHistoryRetentionDays(days: Int) {
        viewModelScope.launch { settingsRepository.setHistoryRetentionDays(days) }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            try {
                historyDao.clearAll()
                _historyEmpty.value = true
                _fileOpState.value = FileOpState.Success(R.string.history_cleared)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Clearing history failed", e)
                _fileOpState.value =
                    FileOpState.Error(
                        R.string.settings_clear_history_title,
                        R.string.message_an_unknown_error_occurred,
                    )
            }
        }
    }

    fun backup(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                BackupRestoreHelper.backup(context.applicationContext, database, uri)
                _fileOpState.value = FileOpState.Success(R.string.backup_success)
            } catch (e: CancellationException) {
                throw e
            } catch (e: UserVisibleException) {
                _fileOpState.value =
                    FileOpState.Error(R.string.settings_backup_title, e.resId, e.args)
            } catch (e: Exception) {
                Log.e(TAG, "Database backup failed", e)
                _fileOpState.value =
                    FileOpState.Error(
                        R.string.settings_backup_title,
                        R.string.message_an_unknown_error_occurred,
                    )
            }
        }
    }

    fun restore(uri: Uri) {
        // Set synchronously, before the coroutine is launched, so the in-progress dialog
        // appears in the same frame as the user's confirmation. The restore performs blocking
        // I/O that does not respond to coroutine cancellation; without the dialog the user
        // could navigate out mid-restore and the restore would still complete with no UI
        // left to show the restart prompt.
        _restoreState.value = RestoreState.InProgress
        viewModelScope.launch(Dispatchers.IO) {
            try {
                BackupRestoreHelper.restore(context.applicationContext, database, uri)
                // Restore replaced the database file; signal the UI to restart the app
                // so Room reinitialises cleanly against the new data.
                _restoreState.value = RestoreState.RestartRequired(restoredDataApplied = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: RestoreClosedDatabaseException) {
                // The replacement failed, but db.close() had already run: the old live database
                // is intact (no data loss) but this process is now holding a closed Room graph,
                // so the next database-backed screen would throw. Restarting is the only
                // recovery, and the dialog must say the restore did NOT succeed.
                Log.e(TAG, "Database restore failed after closing Room; restarting to recover", e)
                _restoreState.value = RestoreState.RestartRequired(restoredDataApplied = false)
            } catch (e: UserVisibleException) {
                _restoreState.value = RestoreState.Error(e.resId, e.args)
            } catch (e: Exception) {
                Log.e(TAG, "Database restore failed", e)
                _restoreState.value = RestoreState.Error(R.string.message_an_unknown_error_occurred)
            }
        }
    }

    fun export(uri: Uri, includeDisabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val (categories, entries) =
                    selectExportData(
                        categories = database.categoryDao().getAllCategories(),
                        entries = database.entryDao().getAllEntries(),
                        includeDisabled = includeDisabled,
                    )
                val writeBom = csvBomEnabled.value
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    CsvExportHelper.export(out, categories, entries, writeBom)
                } ?: throw IOException("Unable to open export destination")
                _fileOpState.value = FileOpState.Success(R.string.export_success)
            } catch (e: CancellationException) {
                throw e
            } catch (e: UserVisibleException) {
                _fileOpState.value = FileOpState.Error(R.string.export_title, e.resId, e.args)
            } catch (e: Exception) {
                Log.e(TAG, "CSV export failed", e)
                _fileOpState.value = FileOpState.Error(R.string.export_title, R.string.export_error)
            }
        }
    }

    fun consumeRestoreState() {
        _restoreState.value = RestoreState.Idle
    }

    fun consumeFileOpState() {
        _fileOpState.value = FileOpState.Idle
    }

    companion object {
        private const val TAG = "SettingsViewModel"
    }
}

class SettingsViewModelFactory(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val database: MainDatabase,
    private val historyDao: HistoryDao,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SettingsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return SettingsViewModel(context, settingsRepository, database, historyDao) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

/**
 * Selection policy deciding which data reaches [CsvExportHelper] during export: disabled categories
 * are excluded unless [includeDisabled] is set, blank entries are always dropped, and entries whose
 * category is not among the selected categories are dropped along with it (so a default export is a
 * convenience view of the selected data, not a partial dump with orphaned rows - the database
 * backup is the full copy).
 *
 * Declared as a top-level function rather than a ViewModel member so the policy can be unit-tested
 * directly on the JVM without constructing a ViewModel (which requires a Context). Filtering must
 * preserve input order - sorting is CsvExportHelper's downstream responsibility.
 */
internal fun selectExportData(
    categories: List<CategoryEntity>,
    entries: List<EntryEntity>,
    includeDisabled: Boolean,
): Pair<List<CategoryEntity>, List<EntryEntity>> {
    val selectedCategories = if (includeDisabled) categories else categories.filter { it.enabled }
    val selectedCategoryIds = selectedCategories.map { it.id }.toSet()
    return selectedCategories to
        entries.filter { it.text.isNotBlank() && it.categoryId in selectedCategoryIds }
}
