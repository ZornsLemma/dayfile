package app.zornslemma.dayfile.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "category",
    // Safety net only: no query looks up by name. Guards the add/rename check-then-insert
    // race with a loud constraint violation; the app enforces the stronger case-insensitive
    // uniqueness, so this index can never reject a legitimate write. Near-zero cost at
    // realistic category counts.
    indices = [Index(value = ["name"], unique = true)],
)
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "ordering") val ordering: Int,
    // Ordering is intentionally NOT declared UNIQUE. The ViewModel rewrites ordering
    // as consecutive values (0..n-1) on every committed reorder and assigns
    // max(ordering) + 1 on insert, keeping values practically unique without a
    // database constraint. A UNIQUE constraint would reject the transient duplicate
    // values that necessarily occur while reordering rows, forcing fragile
    // workarounds. Both CategoryDao queries order by `ordering ASC, id ASC` so that
    // any unexpected duplicate (from a bug or an externally edited database) is still
    // displayed deterministically. Persistence is deferred to drag-end, not per-move,
    // to avoid write races.

    @ColumnInfo(name = "enabled") val enabled: Boolean,
    @ColumnInfo(name = "auto_correct") val autoCorrect: Boolean = true,
    @ColumnInfo(name = "capitalization")
    val capitalization: CapitalizationMode = CapitalizationMode.NONE,
)
