package app.zornslemma.dayfile.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "history_category")
data class HistoryCategoryEntity(
    @PrimaryKey val id: Long,
    @ColumnInfo(name = "name") val name: String,
)
