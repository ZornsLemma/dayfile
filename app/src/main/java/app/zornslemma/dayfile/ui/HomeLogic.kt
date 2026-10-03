package app.zornslemma.dayfile.ui

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * Pure transformation logic for home-screen date and pending-key handling, extracted from
 * [HomeViewModel] so it can be unit-tested on the JVM without Android dependencies.
 */
object HomeLogic {

    /**
     * Computes the logical day for a given instant, using the configured day-start time. If the
     * current time is before [dayStart], the logical day is the previous calendar day; otherwise it
     * is the current calendar day. This implements the "logical days" concept from SPEC.md (e.g. an
     * entry at 00:30 with day-start 04:00 belongs to the previous day).
     */
    fun logicalDateFor(now: LocalDateTime, dayStart: LocalTime): LocalDate {
        return if (now.toLocalTime().isBefore(dayStart)) {
            now.toLocalDate().minusDays(1)
        } else {
            now.toLocalDate()
        }
    }

    /**
     * Decides whether a reset should run based on the background timestamp supplied by
     * [ResetViewModel]. Returns true if no timestamp is available (e.g. first launch, or a process
     * death while the task was still in the foreground) or if real time since the background is at
     * least [timeoutMs]. This encapsulates the 10-minute grace-period rule and does not itself own
     * the timestamp's lifetime: [ResetViewModel] keeps it in a SavedStateHandle, which survives
     * process death while Android retains the task and is discarded when the task is removed. See
     * [ResetViewModel] for the full session-lifetime rationale.
     */
    fun shouldReset(backgroundTimestamp: Long?, now: Long, timeoutMs: Long): Boolean {
        if (backgroundTimestamp == null) return true
        val elapsed = now - backgroundTimestamp
        return elapsed >= timeoutMs
    }

    /**
     * Encodes [date] as the millisecond value Material3's DatePicker uses to represent it. The
     * DatePicker API has no notion of a calendar date: it exchanges epoch-millisecond values, under
     * the fixed convention that such a value means UTC midnight of the selected date. We have no
     * interest in instants or timezones here - we simply want the picker to highlight [date] - so
     * we speak its dialect by encoding the date as UTC midnight.
     *
     * This function and [datePickerMillisToLocalDate] form a matched pair and must both stick to
     * the UTC-midnight convention. Changing only one of them to use the device's local timezone
     * silently shifts dates by one day for users outside UTC, and such bugs may never surface on a
     * CI machine running in UTC.
     */
    fun localDateToDatePickerMillis(date: LocalDate): Long =
        date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    /**
     * Recovers the calendar date denoted by a DatePicker millisecond value, i.e. the inverse of
     * [localDateToDatePickerMillis]: the value is interpreted as UTC midnight and reduced to its
     * date, discarding any sub-day component. See there for why UTC (not local time) is required.
     */
    fun datePickerMillisToLocalDate(millis: Long): LocalDate =
        Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
}
