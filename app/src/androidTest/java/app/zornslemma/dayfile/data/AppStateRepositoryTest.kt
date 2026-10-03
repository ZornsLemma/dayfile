package app.zornslemma.dayfile.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class AppStateRepositoryTest {

    private lateinit var context: Context
    private lateinit var repository: AppStateRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        repository = AppStateRepository(context)
    }

    @After
    fun teardown() = runBlocking {
        // Leave the shared app-state DataStore in a known state for whichever suite or run
        // comes next: no manual lock, and no stale selection. The selected date has no "clear"
        // operation, but every assertion below writes the value it reads, so values left behind
        // by other tests or manual use cannot affect these tests.
        repository.setProtectionOverride(null)
    }

    @Test
    fun selectedDateRoundTrips() = runBlocking {
        // Pins the repository's ISO date string serialisation on both the write and read
        // paths. Preferences DataStore cannot hold a LocalDate directly, so the string format
        // IS the storage contract; a write/read mismatch fails loudly (LocalDate.parse rejects
        // anything that is not an ISO yyyy-MM-DD string) rather than silently corrupting
        // stored dates.
        repository.setSelectedDate(LocalDate.of(2026, 7, 20))
        assertEquals(LocalDate.of(2026, 7, 20), repository.selectedDate.first())

        repository.setSelectedDate(LocalDate.of(2026, 8, 1))
        assertEquals(LocalDate.of(2026, 8, 1), repository.selectedDate.first())
    }

    @Test
    fun protectionOverrideIsDroppedBySetSelectedDate() = runBlocking {
        // The single date-writing funnel drops any manual override in the same atomic write, so
        // the new selection arrives with its default protection state. This pins that pairing.
        repository.setProtectionOverride(true)
        assertEquals(true, repository.protectionOverride.first())

        repository.setSelectedDate(LocalDate.of(2026, 8, 1))
        assertEquals(null, repository.protectionOverride.first())
    }
}
