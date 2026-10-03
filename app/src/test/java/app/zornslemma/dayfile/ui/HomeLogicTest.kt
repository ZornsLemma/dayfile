package app.zornslemma.dayfile.ui

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeLogicTest {

    @Test
    fun `logicalDate is previous day when time before day start`() {
        val now = LocalDateTime.of(2026, 7, 20, 3, 59)
        val result = HomeLogic.logicalDateFor(now, LocalTime.of(4, 0))
        assertEquals(LocalDate.of(2026, 7, 19), result)
    }

    @Test
    fun `logicalDate is current day at exactly day start`() {
        val now = LocalDateTime.of(2026, 7, 20, 4, 0)
        val result = HomeLogic.logicalDateFor(now, LocalTime.of(4, 0))
        assertEquals(LocalDate.of(2026, 7, 20), result)
    }

    @Test
    fun `logicalDate is current day when time after day start`() {
        val now = LocalDateTime.of(2026, 7, 20, 23, 0)
        val result = HomeLogic.logicalDateFor(now, LocalTime.of(4, 0))
        assertEquals(LocalDate.of(2026, 7, 20), result)
    }

    @Test
    fun `logicalDate respects non midnight day start`() {
        val now = LocalDateTime.of(2026, 7, 20, 12, 30)
        val result = HomeLogic.logicalDateFor(now, LocalTime.of(13, 0))
        assertEquals(LocalDate.of(2026, 7, 19), result)
    }

    @Test
    fun `shouldPrune true when background over timeout`() {
        val timeout = 10 * 60 * 1000L
        assertTrue(HomeLogic.shouldReset(1000L, 1000L + timeout + 1, timeout))
    }

    @Test
    fun `shouldPrune false within timeout`() {
        val timeout = 10 * 60 * 1000L
        assertFalse(HomeLogic.shouldReset(1000L, 1000L + 1000, timeout))
    }

    @Test
    fun `shouldPrune true when no background timestamp`() {
        val timeout = 10 * 60 * 1000L
        assertTrue(HomeLogic.shouldReset(null, 999L, timeout))
    }

    @Test
    fun `date picker millis round trip preserves date`() {
        val dates =
            listOf(
                LocalDate.of(1969, 12, 31),
                LocalDate.of(1970, 1, 1),
                LocalDate.of(1999, 12, 31),
                LocalDate.of(2026, 7, 20),
                LocalDate.of(2100, 2, 28),
            )
        for (date in dates) {
            assertEquals(
                date,
                HomeLogic.datePickerMillisToLocalDate(HomeLogic.localDateToDatePickerMillis(date)),
            )
        }
    }

    @Test
    fun `date picker millis just after midnight still map to same date`() {
        val date = LocalDate.of(2026, 7, 20)
        assertEquals(
            date,
            HomeLogic.datePickerMillisToLocalDate(HomeLogic.localDateToDatePickerMillis(date) + 1),
        )
    }

    @Test
    fun `datePicker conversions do not depend on the device timezone`() {
        // 2026-07-20T00:00:00Z is 1784505600000 milliseconds after the Unix epoch. Asserting the
        // literal (rather than computing the expectation with java.time) keeps this test
        // independent of the code under test, pinning the UTC-midnight convention that the
        // Material3 DatePicker expects.
        //
        // We run the assertions under four timezones, one per offset class (zero, large positive,
        // fractional positive, large negative). A correct implementation gives identical answers
        // in all of them, so the plausible future bug of switching either conversion to the
        // device's local timezone is caught here on every machine, rather than depending on which
        // timezone the machine running the tests happens to be in.
        //
        // This temporarily mutates the JVM-global default TimeZone. Under normal sequential test
        // execution that is safe: the change never escapes this method (it is restored in the
        // finally block below) so no other test observes it. If intra-JVM parallel test execution
        // is ever enabled, concurrently-running tests could observe the modified zone - revisit
        // this test before enabling such a configuration.
        val date = LocalDate.of(2026, 7, 20)
        val zones = listOf("UTC", "Pacific/Auckland", "Asia/Kolkata", "Pacific/Honolulu")
        val original = TimeZone.getDefault()
        try {
            for (zoneId in zones) {
                TimeZone.setDefault(TimeZone.getTimeZone(zoneId))
                assertEquals(
                    "localDateToDatePickerMillis wrong under $zoneId",
                    1784505600000L,
                    HomeLogic.localDateToDatePickerMillis(date),
                )
                assertEquals(
                    "datePickerMillisToLocalDate wrong under $zoneId",
                    date,
                    HomeLogic.datePickerMillisToLocalDate(1784505600000L),
                )
            }
        } finally {
            TimeZone.setDefault(original)
        }
    }
}
