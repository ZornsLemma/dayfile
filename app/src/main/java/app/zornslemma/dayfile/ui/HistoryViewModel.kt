package app.zornslemma.dayfile.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.zornslemma.dayfile.data.AppStateRepository
import app.zornslemma.dayfile.data.CategoryDao
import app.zornslemma.dayfile.data.HistoryDao
import java.time.LocalDate
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

// HistoryDisplayItem has the category name denormalised into it. This is fine as it's purely
// in-memory UI display state. Entry fields (id/text/savedAt) come first and category-derived
// fields (categoryId/categoryName/categoryDeleted) last, because the screen touches them in
// those groupings: HistoryItem reads the entry fields, the category header block reads the
// category fields. Names stay raw; the "(deleted, ID n)" marker is applied at render time.
data class HistoryDisplayItem(
    val id: Long,
    val text: String,
    val savedAt: Long,
    val categoryId: Long,
    val categoryName: String,
    // Carried so the screen can label deleted categories at render time (names stay raw).
    val categoryDeleted: Boolean = false,
)

data class CategoryOption(
    val id: Long?, // null means "All"
    val name: String,
    val deleted: Boolean,
)

data class HistoryUiState(
    val filterCategoryId: Long?,
    val items: List<HistoryDisplayItem>,
    // Dropdown options for the current "include deleted" setting, already visibility-filtered
    // (no "All" sentinel; that is presentation state). Names are raw - the "deleted" marker is
    // formatted at display time. includeDeleted is carried for the switch's checked state.
    val categoryOptions: List<CategoryOption>,
    val includeDeleted: Boolean,
)

// ENHANCE: Disabled but not deleted categories cannot be seen in history. Arguably this is a gap,
// since you can see (by enabling the "include deleted categories" switch) truly deleted categories.
// This is probably fine, and of course you can temporarily re-enable the category if you wish,
// whereas you can't un-delete a category. Theoretically there could be an "include disabled
// categories" switch just as there is for deleted categories. My current inclination is that this
// isn't desirable - it complicates the UI and takes screen space for something unlikely to be
// useful and for which there is a workaround - but maybe worth re-considering later.

// ENHANCE: Deleted categories are currently visible (with "include deleted categories" enabled)
// forever, and there is no way to purge these. Earlier AI versions of the code did hide deleted
// categories which had no associated history entries. This feels a little confusing (you delete a
// category which never got used, you go to the history view, enable deleted categories, and you
// still can't see it even though you expect it to be there) but I can see benefits over time as
// deleted categories accumulate - it's really not nice to have some utter junk category you created
// by accident and immediately deleted sticking around forever. It's possible there's a middle
// ground but I also don't want to complicate the UI/the user's mental model for almost no benefit/
// Come back to this later fresh and decide how it ought to work. (The implementation here might
// be as simple as adding a "WHERE id in (SELECT DISTINCT category_id FROM history_entry)" to a
// version of HistoryDao.observeAllCategories(). The implementation is not the hard part here
// however it's done - it's the underlying model and associated UI design.)

// ENHANCE: How should we sort the category list? I think it is reasonable to sort by the
// user-defined ordering at least when there's no deleted categories. That said, it would likely
// also be defensible to sort alphabetically on name (case-insensitively of course) even then. We
// could sort the deleted categories by name instead of ID. We could sort the deleted categories in
// with the non-deleted categories when they're present. There are consistency arguments in various
// directions here too. I don't think what I have is terrible (including deleted categories is
// already something of a rare case) but may want to reconsider how this should work later on.

class HistoryViewModel(
    private val historyDao: HistoryDao,
    private val categoryDao: CategoryDao,
    private val appStateRepository: AppStateRepository,
    private val date: LocalDate,
) : ViewModel() {
    // Private: the screen consumes options via HistoryUiState rather than collecting this
    // directly, so the screen has a single flow to collect.
    private val categoryListFlow =
        combine(categoryDao.observeAllCategories(), historyDao.observeAllCategories()) {
            allLiveCategoryList,
            historyCategoryList ->
            HistoryLogic.buildCategoryOptions(allLiveCategoryList, historyCategoryList)
        }

    val uiStateFlow =
        combine(
                appStateRepository.historyFilterIncludeDeleted,
                appStateRepository.historyFilterCategory,
                categoryListFlow,
            ) { includeDeleted, rawFilterCategory, categoryList ->
                Triple(includeDeleted, rawFilterCategory, categoryList)
            }
            .flatMapLatest { (includeDeleted, rawFilterCategory, categoryList) ->
                val filterCategory =
                    HistoryLogic.resolveEffectiveFilter(
                        rawFilterCategory,
                        categoryList,
                        includeDeleted,
                    )

                // Self-heal the persisted filter: if it no longer resolves against the current
                // category list and "include deleted" setting, write null back to storage. This
                // covers the filtered category having been deleted (or disabled) out-of-band, and
                // a deleted-category filter surviving process death with "include deleted" off -
                // in both cases the stale selection would otherwise resurrect whenever "include
                // deleted" is switched back on. The display already falls back to "All" here, so
                // this merely aligns stored state with what the user sees. Fire-and-forget: the
                // write must survive this flow chain being restarted or cancelled.
                if (rawFilterCategory != null && filterCategory == null) {
                    viewModelScope.launch { appStateRepository.setHistoryFilterCategory(null) }
                }

                val categoryOptions = HistoryLogic.visibleOptions(categoryList, includeDeleted)

                val historyEntryFlow =
                    if (filterCategory == null) {
                        historyDao.observeHistoryForDate(date)
                    } else {
                        historyDao.observeHistoryForDateAndCategory(date, filterCategory)
                    }

                historyEntryFlow.map { dataList ->
                    HistoryUiState(
                        filterCategoryId = filterCategory,
                        items =
                            HistoryLogic.collapsePrefixes(
                                HistoryLogic.mapEntriesToDisplayItems(
                                    dataList,
                                    categoryList,
                                    includeDeleted,
                                )
                            ),
                        categoryOptions = categoryOptions,
                        includeDeleted = includeDeleted,
                    )
                }
            }

    // We could use .flowOn(Dispatchers.Default) here, but it's not clear there's really enough
    // work being done to justify it.

    fun setSelectedCategory(categoryId: Long?) {
        viewModelScope.launch { appStateRepository.setHistoryFilterCategory(categoryId) }
    }

    fun setIncludeDeleted(include: Boolean) {
        viewModelScope.launch { appStateRepository.setHistoryFilterIncludeDeleted(include) }
    }
}

class HistoryViewModelFactory(
    private val historyDao: HistoryDao,
    private val categoryDao: CategoryDao,
    private val appStateRepository: AppStateRepository,
    private val date: LocalDate,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(HistoryViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return HistoryViewModel(historyDao, categoryDao, appStateRepository, date) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
