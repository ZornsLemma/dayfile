package app.zornslemma.dayfile.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDate

// We do *not* have a foreign key relationship between history_entry.category_id and
// history_category.id. This is conceptually reasonable and would offer a small amount of data
// validation. However, it creates an awkward cross-database integrity situation that might prevent
// insertion of history entries:
// - The user adds category Foo.
// - We create Foo successfully in the main database but are interrupted before we create Foo
//   successfully in the history database. (We cannot use a transaction to prevent this across
//   two separate databases.)
// - The user edits an entry for category Foo.
// - We try to record it in the history database and hit a referential integrity violation because
//   the category does not exist there.
//
// We could include fallback logic to deal with this but the corner cases start to multiply, and
// it's far more trouble than it's worth. By not insisting on referential integrity, treating the
// main database as authoritative for all non-deleted categories and carefully ordering the
// operations on both databases when we delete a category, we can guarantee actual consistency for
// the user without complex code.

@Entity(
    tableName = "history_entry",
    // (date, category_id) serves observeHistoryForDate and observeHistoryForDateAndCategory;
    // saved_at serves pruneOld.
    indices = [Index(value = ["date", "category_id"]), Index(value = ["saved_at"])],
)
data class HistoryEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "category_id") val categoryId: Long,
    @ColumnInfo(name = "date") val date: LocalDate,
    @ColumnInfo(name = "text") val text: String,
    @ColumnInfo(name = "saved_at") val savedAt: Long,
)
