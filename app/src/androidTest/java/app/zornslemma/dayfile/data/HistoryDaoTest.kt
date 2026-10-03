package app.zornslemma.dayfile.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

// Deliberately minimal suite. Room validates every query against the schema at compile time, so
// these tests do not re-check generic SQL semantics such as filtering, counting or ordering. They
// pin only decisions with distinct silent failure modes: @Upsert update-on-conflict wiring and
// the inclusive retention cutoff.
class HistoryDaoTest {

    private lateinit var db: HistoryDatabase
    private lateinit var dao: HistoryDao

    @Before
    fun setup() {
        db =
            Room.inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    HistoryDatabase::class.java,
                )
                .build()
        dao = db.historyDao()
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun upsertCategoryInsertsAndUpdates() = runBlocking {
        dao.upsertCategory(HistoryCategoryEntity(id = 4, name = "Exercise"))
        assertEquals(
            listOf(HistoryCategoryEntity(id = 4, name = "Exercise")),
            dao.observeAllCategories().first(),
        )

        // The repository's rename path calls upsertCategory a second time for an id already in
        // the archive, which only works because @Upsert resolves the conflict as an update - a
        // plain @Insert would throw here.
        dao.upsertCategory(HistoryCategoryEntity(id = 4, name = "Exercise (renamed)"))
        assertEquals(
            listOf(HistoryCategoryEntity(id = 4, name = "Exercise (renamed)")),
            dao.observeAllCategories().first(),
        )
    }

    @Test
    fun pruneOldRetainsEntryAtExactCutoff() = runBlocking {
        // "Keep history for N days" is inclusive of the boundary: an entry whose savedAt
        // equals the cutoff is still within the retention window and must NOT be deleted.
        // The SQL uses strict "<", so savedAt == cutoff is retained.
        dao.insertHistory(
            HistoryEntryEntity(
                categoryId = 1,
                date = LocalDate.parse("2026-07-20"),
                text = "at",
                savedAt = 200,
            )
        )
        dao.insertHistory(
            HistoryEntryEntity(
                categoryId = 1,
                date = LocalDate.parse("2026-07-20"),
                text = "below",
                savedAt = 199,
            )
        )
        dao.pruneOld(cutoff = 200)
        val rows = dao.observeHistoryForDate(LocalDate.parse("2026-07-20")).first()
        assertEquals(setOf("at" to 200L), rows.map { it.text to it.savedAt }.toSet())
    }
}
