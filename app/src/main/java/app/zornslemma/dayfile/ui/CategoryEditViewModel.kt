package app.zornslemma.dayfile.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.zornslemma.dayfile.R
import app.zornslemma.dayfile.data.CapitalizationMode
import app.zornslemma.dayfile.data.CategoryEntity
import app.zornslemma.dayfile.data.CategoryRepository
import app.zornslemma.dayfile.data.EntryDao
import app.zornslemma.dayfile.data.EntryStats
import app.zornslemma.dayfile.data.UserVisibleException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CategoryEditViewModel(
    private val categoryRepository: CategoryRepository,
    private val entryDao: EntryDao,
) : ViewModel() {

    data class DeleteDialogState(val category: CategoryEntity, val stats: EntryStats)

    private val _categories = MutableStateFlow<List<CategoryEntity>>(emptyList())
    val categories: StateFlow<List<CategoryEntity>> = _categories.asStateFlow()

    // Room's first emission is asynchronous. Add/edit form state is a one-time snapshot of an
    // existing category, so the form must distinguish "the list has not loaded yet" from
    // "this category genuinely does not exist". Without this, a process-death restoration can
    // compose an edit form before Room supplies its category, making rememberTextFieldState()
    // and rememberSaveable capture placeholder defaults rather than the real values.
    //
    // This latch is deliberately one-way: once the first emission has arrived it stays true for
    // the ViewModel's life, and later emissions update [categories] without touching it. Resetting
    // it would re-gate an already-built form every time the list changed, which would tear down
    // and re-seed live form state. The consequence is that "loaded" and "this category still
    // exists" are separate questions - the add/edit screen answers the second itself, and treats a
    // loaded list without the requested id as a stale route to be popped. In practice that only
    // happens for a genuinely stale route: the delete dialog lives on the category list, which is
    // not composed while the edit form is on top, and a restore restarts the process.
    private val _categoriesLoaded = MutableStateFlow(false)
    val categoriesLoaded: StateFlow<Boolean> = _categoriesLoaded.asStateFlow()

    // Every database write in this class is fire-and-forget from the UI's point of view, and
    // viewModelScope installs no CoroutineExceptionHandler, so an escaping exception reaches the
    // default uncaught handler and kills the process rather than failing one operation. That is a
    // poor trade here for two reasons specific to this screen: the UNIQUE index on category.name
    // (see CategoryEntity) exists precisely to turn the check-then-insert race into a loud
    // SQLiteConstraintException, and a stale edit route reaches the missing-category failure in
    // CategoryRepository.upsertCategory(). Both were crashes before this state existed.
    //
    // SettingsViewModel already maps this class of failure onto a message the user sees; this is
    // the same treatment for category operations. The state is a string resource ID rather than
    // text so the data layer can choose the wording and the UI localises it.
    private val _errorMessageRes = MutableStateFlow<Int?>(null)
    val errorMessageRes: StateFlow<Int?> = _errorMessageRes.asStateFlow()

    fun consumeError() {
        _errorMessageRes.value = null
    }

    // Single funnel for the async work below. Cancellation is rethrown rather than reported:
    // it means the ViewModel is going away, not that the user's operation failed. A
    // UserVisibleException carries its own wording; anything else is a defect or an I/O failure
    // we have no specific message for, and gets the generic one plus a log.
    private fun launchCatching(operation: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: UserVisibleException) {
                Log.e(TAG, "$operation failed", e)
                _errorMessageRes.value = e.resId
            } catch (e: Exception) {
                Log.e(TAG, "$operation failed", e)
                _errorMessageRes.value = R.string.message_an_unknown_error_occurred
            }
        }
    }

    init {
        launchCatching("Observing categories") {
            categoryRepository.observeAllCategories().collect { cats ->
                // Publish the snapshot before declaring it loaded. The add/edit screen gates only
                // on categoriesLoaded, so this ordering guarantees that its first loaded emission
                // already contains the values it uses to initialize the form.
                _categories.value = cats
                _categoriesLoaded.value = true
            }
        }
    }

    // Reassigns consecutive ordering based on list position and persists immediately.
    // Used for discrete actions (button/keyboard moves) where a single write is safe.
    private fun persistOrdering(newList: List<CategoryEntity>) {
        val updated = newList.mapIndexed { index, cat -> cat.copy(ordering = index) }
        _categories.value = updated
        launchCatching("Persisting category order") {
            categoryRepository.updateCategoryOrder(updated)
        }
    }

    // Updates the in-memory list only. The UI calls commitOrder() when a drag ends,
    // so we issue a single database write per gesture instead of one per moved slot.
    fun reorder(from: Int, to: Int) {
        if (from == to) return
        val list = _categories.value.toMutableList()
        if (from < 0 || from >= list.size || to < 0 || to >= list.size) return
        val item = list.removeAt(from)
        list.add(to, item)
        _categories.value = list.mapIndexed { index, cat -> cat.copy(ordering = index) }
    }

    // Persists the current in-memory ordering. Call this when a drag gesture finishes.
    fun commitOrder() {
        launchCatching("Persisting category order") {
            categoryRepository.updateCategoryOrder(_categories.value)
        }
    }

    fun moveUp(categoryId: Long) {
        val list = _categories.value.toMutableList()
        val index = list.indexOfFirst { it.id == categoryId }
        if (index > 0) {
            list.add(index - 1, list.removeAt(index))
            persistOrdering(list)
        }
    }

    fun moveDown(categoryId: Long) {
        val list = _categories.value.toMutableList()
        val index = list.indexOfFirst { it.id == categoryId }
        if (index >= 0 && index < list.lastIndex) {
            list.add(index + 1, list.removeAt(index))
            persistOrdering(list)
        }
    }

    fun toggleEnabled(categoryId: Long) {
        val list = _categories.value.toMutableList()
        val index = list.indexOfFirst { it.id == categoryId }
        if (index >= 0) {
            val updated = list[index].copy(enabled = !list[index].enabled)
            list[index] = updated
            _categories.value = list
            launchCatching("Toggling category") {
                categoryRepository.updateCategoryOrder(listOf(updated))
            }
        }
    }

    fun upsertCategory(
        name: String,
        autoCorrect: Boolean,
        capitalization: CapitalizationMode,
        categoryId: Long?,
    ) {
        launchCatching("Saving category") {
            categoryRepository.upsertCategory(name, autoCorrect, capitalization, categoryId)
            // The observed flow from categoryDao will update _categories automatically
        }
    }

    private val _deleteDialogState = MutableStateFlow<DeleteDialogState?>(null)
    val deleteDialogState: StateFlow<DeleteDialogState?> = _deleteDialogState.asStateFlow()

    // Cancels any in-flight stats fetch so a newer request always wins: without this, two
    // overlapping fetches could let the slower, stale one land last and show stats for the
    // wrong category. Near-unreachable in practice (the menu closes after each selection and
    // the query is a single indexed lookup), but the guard is cheap and pinned by a JVM test.
    private var deleteStatsJob: Job? = null

    fun openDeleteDialog(category: CategoryEntity) {
        deleteStatsJob?.cancel()
        deleteStatsJob =
            viewModelScope.launch {
                // The stats query feeds a confirmation dialog, so a failure here is reported the
                // same way as any other rather than crashing: no dialog, and an error instead.
                try {
                    val stats = entryDao.getEntryStatsForCategory(category.id)
                    _deleteDialogState.value = DeleteDialogState(category = category, stats = stats)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Loading delete statistics failed", e)
                    _errorMessageRes.value = R.string.message_an_unknown_error_occurred
                }
            }
    }

    fun closeDeleteDialog() {
        _deleteDialogState.value = null
    }

    fun confirmDelete() {
        val state = _deleteDialogState.value ?: return
        // Close the confirmation up front rather than on success. Leaving it up while a failure
        // dialog appeared over it would stack two dialogs on screen, and the delete is a one-shot
        // either way - if it failed, the error dialog is the only thing the user needs to see.
        _deleteDialogState.value = null
        launchCatching("Deleting category") { categoryRepository.deleteCategory(state.category) }
    }

    companion object {
        private const val TAG = "CategoryEditViewModel"
    }
}

class CategoryEditViewModelFactory(
    private val categoryRepository: CategoryRepository,
    private val entryDao: EntryDao,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(CategoryEditViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return CategoryEditViewModel(categoryRepository, entryDao) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
