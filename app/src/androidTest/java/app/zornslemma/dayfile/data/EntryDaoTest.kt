package app.zornslemma.dayfile.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class EntryDaoTest {

    private lateinit var db: MainDatabase
    private lateinit var dao: EntryDao
    private lateinit var categoryDao: CategoryDao

    @Before
    fun setup() {
        db =
            Room.inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    MainDatabase::class.java,
                )
                .build()
        dao = db.entryDao()
        categoryDao = db.categoryDao()
    }

    @After
    fun teardown() {
        db.close()
    }

    // NOTE: This test class is intentionally minimal. It covers only the non-trivial query
    // (getEntryStatsForCategory) which performs aggregation and date-range calculation. The other
    // DAO methods are simple Room-generated inserts/queries already exercised indirectly via
    // BackupRestoreHelperTest (the ViewModel tests use fakes, so they exercise no real DAO code).
    // Adding trivial tests for them would not increase practical confidence (see DEVELOPMENT.md:
    // "Tests should provide confidence rather than merely increasing coverage statistics").

    @Test
    fun getEntryStatsForCategoryReturnsCountAndDateRange() = runBlocking {
        categoryDao.insert(CategoryEntity(name = "Diet", ordering = 0, enabled = true))
        dao.insert(EntryEntity(categoryId = 1, date = LocalDate.parse("2026-01-02"), text = "b"))
        dao.insert(EntryEntity(categoryId = 1, date = LocalDate.parse("2026-01-01"), text = "a"))
        dao.insert(EntryEntity(categoryId = 1, date = LocalDate.parse("2026-01-03"), text = "c"))

        assertEquals(
            EntryStats(3, LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-03")),
            dao.getEntryStatsForCategory(1),
        )
    }

    @Test
    fun getEntryStatsForCategoryReturnsZeroForEmpty() = runBlocking {
        assertEquals(EntryStats(0, null, null), dao.getEntryStatsForCategory(1))
    }
}
