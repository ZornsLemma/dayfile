package app.zornslemma.dayfile.ui

import app.zornslemma.dayfile.data.CategoryEntity
import app.zornslemma.dayfile.data.HistoryCategoryEntity
import app.zornslemma.dayfile.data.HistoryEntryEntity

// Thin display-policy layer between the databases and the generic HistoryPrefixCompactor. HPC is
// deliberately category-blind and unordered; the app-specific decisions live here rather than in
// HistoryViewModel so they stay unit-testable on the JVM without Android dependencies:
//   - prefix compaction never acts across category boundaries (grouping is by categoryId, never
//     by display name - see the colliding-display-names case pinned in HistoryLogicTest),
//   - same-text collisions resolve newest-wins (savedAt, then id),
//   - surviving snapshots are returned globally newest-first,
//   - the filter dropdown merges live categories (enabled only, user ordering) with archived
//     deleted ones (raw names, ordered by id); live-but-disabled categories are never
//     re-introduced from the archive,
//   - which options are visible for the current "include deleted" setting is decided here
//     (visibleOptions), in the same terms as the resolution rule, so a filter the ViewModel
//     accepts can always be named by an option the dropdown shows,
//   - the persisted raw filter resolves against those options (this defines the ViewModel's
//     self-heal trigger condition for stale filters),
//   - raw history rows project to display items, silently dropping rows whose category is
//     unknown (e.g. after a restore) or currently hidden, and carrying the deleted flag so the
//     screen can label deleted categories at render time without re-deriving it.
object HistoryLogic {

    fun collapsePrefixes(items: List<HistoryDisplayItem>): List<HistoryDisplayItem> {
        // We use the IDs just as tiebreakers for consistency if it ever matters. We do insert
        // history entries in chronological order so we could likely use just the IDs to get a
        // perfect sort, but this logic should be completely equivalent and feels cleaner/safer.
        val historyComparator = compareBy<HistoryDisplayItem> { it.savedAt }.thenBy { it.id }

        val compactorByCategory = mutableMapOf<Long, HistoryPrefixCompactor<HistoryDisplayItem>>()
        for (item in items) {
            compactorByCategory
                .getOrPut(item.categoryId) {
                    HistoryPrefixCompactor(
                        keySelector = { it.text },
                        resolver = { a, b -> if (historyComparator.compare(a, b) > 0) a else b },
                    )
                }
                .add(item)
        }
        val kept = compactorByCategory.values.flatMap { it.filter { true } }
        return kept.sortedWith(historyComparator.reversed())
    }

    // Merges live categories and archived (deleted) categories into the dropdown option list.
    // Archived options carry their raw name; the user-visible "deleted" marker is formatted at
    // display time (see HistoryScreen). Keeping Android resources out of this object is what
    // allows it to remain JVM-testable.
    fun buildCategoryOptions(
        liveCategories: List<CategoryEntity>,
        archivedCategories: List<HistoryCategoryEntity>,
    ): List<CategoryOption> {
        val liveCategoryIds = liveCategories.map { it.id }.toSet()
        val liveOptions =
            liveCategories
                .filter { it.enabled }
                .sortedBy { it.ordering }
                .map { CategoryOption(it.id, it.name, deleted = false) }
        val archivedOptions =
            archivedCategories
                // Don't "re-introduce" live-but-disabled categories from the history archive.
                .filter { it.id !in liveCategoryIds }
                .sortedBy { it.id }
                .map { CategoryOption(it.id, it.name, deleted = true) }
        return liveOptions + archivedOptions
    }

    // Options visible in the dropdown for the current "include deleted" setting. Centralised
    // next to resolveEffectiveFilter, which must agree with it: a raw filter resolved against
    // the full list has to correspond to an option in the visible list, otherwise the dropdown
    // could not name the filter the ViewModel is actually applying.
    fun visibleOptions(
        categoryOptions: List<CategoryOption>,
        includeDeleted: Boolean,
    ): List<CategoryOption> = categoryOptions.filter { includeDeleted || !it.deleted }

    // Resolves the persisted raw filter against the current options and "include deleted"
    // setting. Returns null when unset or unresolvable - the caller treats null as "All", and
    // a non-null raw filter resolving to null is precisely the condition that triggers the
    // ViewModel's self-heal write.
    fun resolveEffectiveFilter(
        rawFilterCategoryId: Long?,
        categoryOptions: List<CategoryOption>,
        includeDeleted: Boolean,
    ): Long? =
        rawFilterCategoryId?.takeIf { raw ->
            categoryOptions.any { it.id == raw && (includeDeleted || !it.deleted) }
        }

    // Projects raw history rows to display items, taking names from the merged option list.
    // Rows whose category is absent from [categoryOptions] (unknown id, e.g. after a restore,
    // or live-but-disabled) are dropped, as are rows of deleted categories unless
    // [includeDeleted] is set. Names stay raw; the deleted flag travels with the item so the
    // screen can apply the marker at render time.
    fun mapEntriesToDisplayItems(
        entries: List<HistoryEntryEntity>,
        categoryOptions: List<CategoryOption>,
        includeDeleted: Boolean,
    ): List<HistoryDisplayItem> {
        val categoryById = categoryOptions.associateBy { it.id }
        return entries.mapNotNull { entry ->
            val category = categoryById[entry.categoryId]
            if (category != null && (includeDeleted || !category.deleted)) {
                HistoryDisplayItem(
                    id = entry.id,
                    text = entry.text,
                    savedAt = entry.savedAt,
                    categoryId = entry.categoryId,
                    categoryName = category.name,
                    categoryDeleted = category.deleted,
                )
            } else {
                null
            }
        }
    }
}
