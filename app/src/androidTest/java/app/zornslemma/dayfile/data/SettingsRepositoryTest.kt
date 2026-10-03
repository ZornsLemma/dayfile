package app.zornslemma.dayfile.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.time.LocalTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

// Deliberately minimal suite. Round-trip tests for pure DataStore pass-throughs (CSV BOM flag,
// retention days) were removed: they verify DataStore rather than any repository logic or
// product decision. Only the day-start mapping has logic of its own worth pinning (the
// minutes-since-midnight integer encoding, with minute precision per SPEC.md).
class SettingsRepositoryTest {

    private lateinit var context: Context
    private lateinit var repository: SettingsRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        repository = SettingsRepository(context)
        // Reset known keys to deterministic values to avoid cross-test leakage via the
        // on-device DataStore (we cannot clear it directly from the test).
        runBlocking { repository.setDayStartTime(LocalTime.of(4, 0)) }
    }

    @After fun teardown() = runBlocking { repository.setDayStartTime(LocalTime.of(4, 0)) }

    @Test
    fun dayStartTimeRoundTripsWithMinutePrecision() = runBlocking {
        repository.setDayStartTime(LocalTime.of(13, 30))
        assertEquals(LocalTime.of(13, 30), repository.dayStartTime.first())
    }

    @Test
    fun dayStartTimeRoundTripsNearMidnight() = runBlocking {
        repository.setDayStartTime(LocalTime.of(23, 59))
        assertEquals(LocalTime.of(23, 59), repository.dayStartTime.first())
    }
}
