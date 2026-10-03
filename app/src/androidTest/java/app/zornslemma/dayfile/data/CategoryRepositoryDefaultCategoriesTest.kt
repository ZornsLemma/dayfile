package app.zornslemma.dayfile.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.zornslemma.dayfile.R
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// Instrumented (not JVM-local) because ensureDefaultCategoriesExist resolves the default
// category names through Context.getString(R.string.default_category_*), so both the code
// under test and the assertions need a real Context carrying the app's resources. Asserting
// against the same R.string IDs keeps these tests locale-proof: they verify that the seeded
// names are the localized defaults, not that the device happens to run in English.
class CategoryRepositoryDefaultCategoriesTest {

    private lateinit var context: Context
    private lateinit var mainDb: MainDatabase
    private lateinit var historyDb: HistoryDatabase
    private lateinit var repository: CategoryRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        mainDb = Room.inMemoryDatabaseBuilder(context, MainDatabase::class.java).build()
        historyDb = Room.inMemoryDatabaseBuilder(context, HistoryDatabase::class.java).build()
        repository = CategoryRepository(mainDb.categoryDao(), historyDb.historyDao())
    }

    @After
    fun teardown() {
        mainDb.close()
        historyDb.close()
    }

    @Test
    fun ensureDefaultCategoriesExistSeedsFourDefaultsAndPrimesHistory() = runBlocking {
        repository.ensureDefaultCategoriesExist(context)

        val categories = mainDb.categoryDao().getAllCategories()
        assertEquals(
            listOf(
                context.getString(R.string.default_category_money),
                context.getString(R.string.default_category_diet),
                context.getString(R.string.default_category_exercise),
                context.getString(R.string.default_category_miscellaneous),
            ),
            categories.map { it.name },
        )
        assertEquals(listOf(0, 1, 2, 3), categories.map { it.ordering })
        assertTrue(categories.all { it.enabled })
        // Whole-map equality: every default must be primed into the history name map under
        // its real auto-generated ID, with no extras.
        assertEquals(
            categories.associate { it.id to it.name },
            historyDb.historyDao().observeAllCategories().first().associate { it.id to it.name },
        )
    }

    @Test
    fun ensureDefaultCategoriesExistDoesNothingWhenCategoriesAlreadyExist() = runBlocking {
        // The user already owns categories (e.g. renamed or deleted some defaults): seeding
        // must only happen at empty-database time, never as a refill alongside user data.
        mainDb.categoryDao().insert(CategoryEntity(name = "Custom", ordering = 0, enabled = true))

        repository.ensureDefaultCategoriesExist(context)

        assertEquals(1, mainDb.categoryDao().getCount())
        assertTrue(historyDb.historyDao().observeAllCategories().first().isEmpty())
    }
}
