package app.zornslemma.dayfile.data

import android.content.Context
import app.zornslemma.dayfile.R
import kotlinx.coroutines.flow.Flow

// The main database is the source of truth for active categories (whether enabled or disabled, as
// long as they are not deleted). The history database maintains a separate category ID->name map so
// that historical entries can still be associated with the category's final name after the category
// itself has been deleted.
//
// While the category is active, this history record is not used for display and is therefore only
// kept in sync on a best-effort basis. It is useful for debugging and inspecting the history
// database, but a failure here does not affect application correctness. Best-effort in practice
// means "we do this update but if it (unexpectedly) crashes, or the app happens to be killed after
// the main database update but before the history update happens, we don't care about the resulting
// inconsistency when the app restarts".
//
// Deletion is different: once the main category row is deleted, the history record becomes the only
// surviving source of the category's name. Therefore the history record must be written
// successfully before the category is deleted.

class CategoryRepository(private val categoryDao: CategoryDao, private val historyDao: HistoryDao) {
    fun observeAllCategories(): Flow<List<CategoryEntity>> = categoryDao.observeAllCategories()

    suspend fun ensureDefaultCategoriesExist(context: Context) {
        if (categoryDao.getCount() == 0) {
            val defaultCategories =
                listOf(
                    CategoryEntity(
                        name = context.getString(R.string.default_category_money),
                        ordering = 0,
                        enabled = true,
                    ),
                    CategoryEntity(
                        name = context.getString(R.string.default_category_diet),
                        ordering = 1,
                        enabled = true,
                    ),
                    CategoryEntity(
                        name = context.getString(R.string.default_category_exercise),
                        ordering = 2,
                        enabled = true,
                    ),
                    CategoryEntity(
                        name = context.getString(R.string.default_category_miscellaneous),
                        ordering = 3,
                        enabled = true,
                    ),
                )
            val categoryIds = categoryDao.insertAll(defaultCategories)
            val insertedCategories =
                defaultCategories.mapIndexed { index, category ->
                    category.copy(id = categoryIds[index])
                }
            // Best-effort: prime the history database's category name map with the default
            // categories. This is not critical for correctness - the main
            // database is the source of truth for active categories and the user will never see
            // the category names from the history database - but it's good for debugging to try
            // to keep this up to date.
            for (cat in insertedCategories) {
                historyDao.upsertCategory(HistoryCategoryEntity(id = cat.id, name = cat.name))
            }
        }
    }

    // Inserts a new category or updates an existing one.
    // For new categories: inserts into main DB, then best-effort primes history name map.
    // For existing categories: updates main DB, then best-effort syncs history name map.
    // Returns the category ID (new or existing).
    suspend fun upsertCategory(
        name: String,
        autoCorrect: Boolean,
        capitalization: CapitalizationMode,
        categoryId: Long?,
    ): Long {
        return if (categoryId != null) {
            // Reachable when the row has gone between the add/edit form reading the category list
            // and Save being tapped (a stale categoryAddEdit/{categoryId} route surviving process
            // death, say). A bare checkNotNull here threw IllegalStateException into an
            // un-guarded viewModelScope.launch, which takes the process down. UserVisibleException
            // is the data layer's way of saying "this failed in a way the UI can report"; the
            // message is log-only, and the caller shows the generic failure message.
            val existingCategory =
                categoryDao.getCategory(categoryId)
                    ?: throw UserVisibleException(
                        R.string.message_an_unknown_error_occurred,
                        message = "Category $categoryId no longer exists; stale edit route?",
                    )
            val updatedCategory =
                existingCategory.copy(
                    name = name,
                    autoCorrect = autoCorrect,
                    capitalization = capitalization,
                )
            categoryDao.updateCategories(listOf(updatedCategory))
            // Best-effort: keep history database's category name map in sync for active categories.
            // The main database is the source of truth; history map is only used for deleted
            // categories.
            historyDao.upsertCategory(HistoryCategoryEntity(id = categoryId, name = name))
            categoryId
        } else {
            val nextOrdering =
                (categoryDao.getAllCategories().maxOfOrNull { it.ordering } ?: -1) + 1
            val newCategoryId =
                categoryDao.insert(
                    CategoryEntity(
                        name = name,
                        ordering = nextOrdering,
                        enabled = true,
                        autoCorrect = autoCorrect,
                        capitalization = capitalization,
                    )
                )
            // Best-effort: prime the history database's category name map for the new category.
            historyDao.upsertCategory(HistoryCategoryEntity(id = newCategoryId, name = name))
            newCategoryId
        }
    }

    // Deletes a category from the main database.
    // CRITICAL: Writes the category's current name to the history database FIRST, before
    // deleting from the main database. This ensures that if the category has any history
    // entries, those entries can be displayed with the correct final name.
    // The history database's history_category is the authoritative name source for DELETED
    // categories (the main database row no longer exists). For active categories, the main
    // database is the source of truth and the history map is a best-effort mirror.
    // Ordering: history upsert -> main DB delete. If the process crashes after the history
    // write but before the main DB delete, the category still exists in the main DB (source
    // of truth) and the history map happens to have the correct name — harmless. If it
    // crashes after the main DB delete, the category is gone and the history map has the
    // correct final name — correct. This ordering eliminates the "stale name in history for
    // deleted category" window.
    suspend fun deleteCategory(category: CategoryEntity) {
        historyDao.upsertCategory(HistoryCategoryEntity(id = category.id, name = category.name))
        categoryDao.delete(category)
    }

    // This doesn't update the history, but that's fine because it is explicitly intended for
    // reorderings only. The performance impact of updating the history table redundantly would be
    // minor, but since we have a very different interface compared to upsertCategory() it's
    // probably as well not to try to force a sharing of code and accept this "accidental"
    // optimisation.
    suspend fun updateCategoryOrder(categories: List<CategoryEntity>) {
        categoryDao.updateCategories(categories)
    }
}
