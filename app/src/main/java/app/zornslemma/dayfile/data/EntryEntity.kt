package app.zornslemma.dayfile.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDate

@Entity(
    tableName = "entry",
    foreignKeys =
        [
            ForeignKey(
                entity = CategoryEntity::class,
                parentColumns = ["id"],
                childColumns = ["category_id"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
    // The unique (category_id, date) index is the business key (one entry per category per
    // logical day) and also serves getEntryForCategoryAndDate, the FK cascade lookup and a
    // covering getEntryStatsForCategory scan. The plain date index serves getEntriesForDate,
    // which cannot use a category_id-leading index.
    indices = [Index(value = ["category_id", "date"], unique = true), Index(value = ["date"])],
)
data class EntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "category_id") val categoryId: Long,
    @ColumnInfo(name = "date") val date: LocalDate,
    @ColumnInfo(name = "text") val text: String,
)
