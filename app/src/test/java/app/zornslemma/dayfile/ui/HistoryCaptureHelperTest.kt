package app.zornslemma.dayfile.ui

import app.zornslemma.dayfile.data.HistoryCategoryEntity
import app.zornslemma.dayfile.data.HistoryDao
import app.zornslemma.dayfile.data.HistoryEntryEntity
import java.time.LocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

// Two pieces of state are easy to wire up wrongly, so they get dedicated tests below: the
// compactor resolver must let the newest offer win same-text collisions (an older-wins resolver
// makes re-offered edits vanish beneath the flush watermark), and the watermark itself must
// advance on every flush so already-written texts are never rewritten.
//
// Two further failure modes get their own tests for the same reason. A flush walks every
// (date, category) key in one pass while ids are handed out globally, so a snapshot offered for
// one key can be dropped by a watermark another key's write has already advanced past. And
// leading/trailing whitespace in an offer is an intermediate keystroke state rather than part of
// the entry, so it must not turn one entry into two visibly distinct history rows.
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryCaptureHelperTest {

    // Margins either side of the production debounce delay (SAVE_DEBOUNCE_MS is 400ms) so the
    // tests can distinguish "inside" the debounce window from "past" it without depending on the
    // exact constant.
    private companion object {
        val DATE = LocalDate.parse("2026-07-20")
        const val CATEGORY_A = 1L
        const val CATEGORY_B = 2L
        const val BASE_TIME_MS = 1_750_000_000_000L
        const val WITHIN_DEBOUNCE_MS = 100L
        const val PAST_DEBOUNCE_MS = 1_000L
    }

    @Test
    fun `blank text produces no history rows`() = runTest {
        val fakeHistory = FakeHistoryDao()
        val helper = createStartedHelper(fakeHistory, clock = { BASE_TIME_MS })

        helper.offerEntryText(DATE, CATEGORY_A, "")
        helper.offerEntryText(DATE, CATEGORY_A, "   ")
        helper.offerEntryText(DATE, CATEGORY_B, "\n\t")
        advanceTimeBy(PAST_DEBOUNCE_MS)
        runCurrent()

        assertEquals(emptyList<HistoryEntryEntity>(), fakeHistory.inserted)
    }

    @Test
    fun `growing prefix within one window collapses to the longer text`() = runTest {
        val fakeHistory = FakeHistoryDao()
        val helper = createStartedHelper(fakeHistory, clock = { BASE_TIME_MS })

        helper.offerEntryText(DATE, CATEGORY_A, "hello wor")
        helper.offerEntryText(DATE, CATEGORY_A, "hello world")
        advanceTimeBy(PAST_DEBOUNCE_MS)
        runCurrent()

        assertEquals(listOf(entry(CATEGORY_A, "hello world")), fakeHistory.inserted)
    }

    @Test
    fun `shrinking text within one window retains the longer pre-edit text`() = runTest {
        val fakeHistory = FakeHistoryDao()
        val helper = createStartedHelper(fakeHistory, clock = { BASE_TIME_MS })

        helper.offerEntryText(DATE, CATEGORY_A, "hello world")
        helper.offerEntryText(DATE, CATEGORY_A, "hello wor")
        advanceTimeBy(PAST_DEBOUNCE_MS)
        runCurrent()

        // The shorter replacement is dropped by the prefix compactor, but the pre-edit text must
        // survive: it is the snapshot the user would want to recover.
        assertEquals(listOf(entry(CATEGORY_A, "hello world")), fakeHistory.inserted)
    }

    @Test
    fun `trailing space then non-space variant collapse to the final text`() = runTest {
        // Scenario: the user types a space, backspaces it and types a letter, so
        // "30 " and "30m" are one entry caught mid-edit rather than two snapshots worth keeping.
        // Leading/trailing whitespace is an intermediate keystroke state, not part of the entry, so
        // the compactor should see "30" and "30m" and supersede the shorter exactly as it would
        // for any other prefix chain.
        val fakeHistory = FakeHistoryDao()
        val helper = createStartedHelper(fakeHistory, clock = { BASE_TIME_MS })

        helper.offerEntryText(DATE, CATEGORY_A, "30 ")
        helper.offerEntryText(DATE, CATEGORY_A, "30m")
        advanceTimeBy(PAST_DEBOUNCE_MS)
        runCurrent()

        assertEquals(listOf(entry(CATEGORY_A, "30m")), fakeHistory.inserted)
    }

    @Test
    fun `leading space then non-space variant collapse to the final text`() = runTest {
        // Leading whitespace is insignificant on the same terms as trailing. A trimEnd()-only
        // implementation would satisfy the trailing-space test while still letting " 30" and
        // "30m" become separate history rows, so this case pins the leading side too.
        val fakeHistory = FakeHistoryDao()
        val helper = createStartedHelper(fakeHistory, clock = { BASE_TIME_MS })

        helper.offerEntryText(DATE, CATEGORY_A, " 30")
        helper.offerEntryText(DATE, CATEGORY_A, "30m")
        advanceTimeBy(PAST_DEBOUNCE_MS)
        runCurrent()

        assertEquals(listOf(entry(CATEGORY_A, "30m")), fakeHistory.inserted)
    }

    @Test
    fun `leading and trailing whitespace is not persisted into history`() = runTest {
        // Pins the stored form itself, which the two collapse tests above cannot distinguish: in
        // both of those the whitespace-bearing variant is compacted away either way, so trimming
        // only the compactor key would satisfy them while leaving the raw text in the row. The
        // recovery text the user copies back should not carry keystroke whitespace.
        val fakeHistory = FakeHistoryDao()
        val helper = createStartedHelper(fakeHistory, clock = { BASE_TIME_MS })

        helper.offerEntryText(DATE, CATEGORY_A, " 30 ")
        advanceTimeBy(PAST_DEBOUNCE_MS)
        runCurrent()

        assertEquals(listOf(entry(CATEGORY_A, "30")), fakeHistory.inserted)
    }

    @Test
    fun `distinct texts within one window are all retained`() = runTest {
        val fakeHistory = FakeHistoryDao()
        val helper = createStartedHelper(fakeHistory, clock = { BASE_TIME_MS })

        helper.offerEntryText(DATE, CATEGORY_A, "alpha")
        helper.offerEntryText(DATE, CATEGORY_A, "beta")
        advanceTimeBy(PAST_DEBOUNCE_MS)
        runCurrent()

        assertEquals(
            listOf(entry(CATEGORY_A, "alpha"), entry(CATEGORY_A, "beta")),
            fakeHistory.inserted,
        )
    }

    @Test
    fun `same text offered twice within one window collapses to one row with the latest savedAt`() =
        runTest {
            val fakeHistory = FakeHistoryDao()
            var now = BASE_TIME_MS
            val helper = createStartedHelper(fakeHistory, clock = { now })

            helper.offerEntryText(DATE, CATEGORY_A, "alpha")
            now = BASE_TIME_MS + 5_000
            helper.offerEntryText(DATE, CATEGORY_A, "alpha")
            advanceTimeBy(PAST_DEBOUNCE_MS)
            runCurrent()

            // The resolver must keep the newer HistoryString on a same-key collision; an older-wins
            // resolver would leave the first offer's savedAt on the surviving row.
            assertEquals(
                listOf(entry(CATEGORY_A, "alpha", savedAt = BASE_TIME_MS + 5_000)),
                fakeHistory.inserted,
            )
        }

    @Test
    fun `already flushed texts are not rewritten by later debounce cycles`() = runTest {
        // Each flush advances lastWrittenHistoryStringId past everything it wrote, so later
        // cycles only write ids above the watermark. Without this, the constant re-offers of
        // unchanged field contents (every date navigation re-offers them) would duplicate rows
        // on every cycle.
        val fakeHistory = FakeHistoryDao()
        val helper = createStartedHelper(fakeHistory, clock = { BASE_TIME_MS })

        helper.offerEntryText(DATE, CATEGORY_A, "alpha")
        advanceTimeBy(PAST_DEBOUNCE_MS)
        runCurrent()
        assertEquals(listOf(entry(CATEGORY_A, "alpha")), fakeHistory.inserted)

        helper.offerEntryText(DATE, CATEGORY_A, "beta")
        // Still inside the fresh debounce window: nothing further written yet.
        advanceTimeBy(WITHIN_DEBOUNCE_MS)
        runCurrent()
        assertEquals(listOf(entry(CATEGORY_A, "alpha")), fakeHistory.inserted)

        advanceTimeBy(PAST_DEBOUNCE_MS)
        runCurrent()
        assertEquals(
            listOf(entry(CATEGORY_A, "alpha"), entry(CATEGORY_A, "beta")),
            fakeHistory.inserted,
        )
    }

    @Test
    fun `same text re-offered in a later window is recorded again`() = runTest {
        val fakeHistory = FakeHistoryDao()
        var now = BASE_TIME_MS
        val helper = createStartedHelper(fakeHistory, clock = { now })

        helper.offerEntryText(DATE, CATEGORY_A, "alpha")
        advanceTimeBy(PAST_DEBOUNCE_MS)
        runCurrent()
        assertEquals(listOf(entry(CATEGORY_A, "alpha")), fakeHistory.inserted)

        now = BASE_TIME_MS + 5_000
        helper.offerEntryText(DATE, CATEGORY_A, "alpha")
        advanceTimeBy(PAST_DEBOUNCE_MS)
        runCurrent()

        // The second write only happens if the resolver kept the newer HistoryString: under an
        // older-wins resolver its id would fall below the flush watermark and the re-edit would
        // be dropped silently.
        assertEquals(
            listOf(
                entry(CATEGORY_A, "alpha", savedAt = BASE_TIME_MS),
                entry(CATEGORY_A, "alpha", savedAt = BASE_TIME_MS + 5_000),
            ),
            fakeHistory.inserted,
        )
    }

    @Test
    fun `offers are routed to the correct date and category`() = runTest {
        val fakeHistory = FakeHistoryDao()
        val helper = createStartedHelper(fakeHistory, clock = { BASE_TIME_MS })

        helper.offerEntryText(LocalDate.parse("2026-07-24"), CATEGORY_A, "money")
        helper.offerEntryText(LocalDate.parse("2026-07-25"), CATEGORY_B, "diet")
        advanceTimeBy(PAST_DEBOUNCE_MS)
        runCurrent()

        // Offers for different (date, category) keys are flushed in map iteration order, which is
        // unspecified, so compare order-insensitively.
        val expected =
            listOf(
                entry(
                    categoryId = CATEGORY_A,
                    text = "money",
                    date = LocalDate.parse("2026-07-24"),
                ),
                entry(categoryId = CATEGORY_B, text = "diet", date = LocalDate.parse("2026-07-25")),
            )
        assertEquals(expected.sortedBy { it.date }, fakeHistory.inserted.sortedBy { it.date })
    }

    @Test
    fun `interleaved offers across keys all reach the database`() = runTest {
        // Ids are handed out globally but each key gets its own compactor, so one flush visits
        // the keys in map insertion order while their *surviving* ids interleave: A's compactor
        // keeps only its longest text, so the flushed ids are 1 (category B) and 2 (category A) and
        // the flush visits them in the order A then B. A single flush watermark advanced by A's
        // write is then already past B's pending id, and B's snapshot is silently dropped - losing
        // exactly the record the history exists to provide. No 400ms gap is needed: debounce
        // restarts on every offer, so a continuous burst of typing across two fields lands here.
        val fakeHistory = FakeHistoryDao()
        val helper = createStartedHelper(fakeHistory, clock = { BASE_TIME_MS })

        helper.offerEntryText(DATE, CATEGORY_A, "abc")
        helper.offerEntryText(DATE, CATEGORY_B, "xyz")
        helper.offerEntryText(DATE, CATEGORY_A, "abcd")
        advanceTimeBy(PAST_DEBOUNCE_MS)
        runCurrent()

        // Whole-list equality, compared order-insensitively as the routing test above does: a flush
        // visits (date, category) keys in map insertion order rather than id order, and nothing
        // depends on the order of one category's rows relative to another's. Rows within a single
        // key are still written in ascending id order, which is all the display-time compaction in
        // HistoryLogic needs to build its antichain.
        val expected =
            listOf(
                entry(categoryId = CATEGORY_A, text = "abcd"),
                entry(categoryId = CATEGORY_B, text = "xyz"),
            )
        assertEquals(
            expected.sortedBy { it.categoryId },
            fakeHistory.inserted.sortedBy { it.categoryId },
        )
    }

    @Test
    fun `savedAt comes from the injected clock`() = runTest {
        val fakeHistory = FakeHistoryDao()
        var now = BASE_TIME_MS
        val helper = createStartedHelper(fakeHistory, clock = { now })

        helper.offerEntryText(DATE, CATEGORY_A, "first")
        now = BASE_TIME_MS + 5_000
        helper.offerEntryText(DATE, CATEGORY_A, "second")
        advanceTimeBy(PAST_DEBOUNCE_MS)
        runCurrent()

        assertEquals(
            listOf(
                entry(CATEGORY_A, "first", savedAt = BASE_TIME_MS),
                entry(CATEGORY_A, "second", savedAt = BASE_TIME_MS + 5_000),
            ),
            fakeHistory.inserted,
        )
    }

    /**
     * Creates a helper whose debounced collector runs in [TestScope.backgroundScope], then gives
     * the collector a chance to subscribe. Without this priming step, offers emitted before the
     * subscription takes effect would be silently dropped, as the shared flow has no replay buffer.
     */
    private fun TestScope.createStartedHelper(
        fakeHistory: FakeHistoryDao,
        clock: () -> Long,
    ): HistoryCaptureHelper {
        val helper = HistoryCaptureHelper(fakeHistory, backgroundScope, clock = clock)
        runCurrent()
        return helper
    }

    private fun entry(
        categoryId: Long,
        text: String,
        date: LocalDate = DATE,
        savedAt: Long = BASE_TIME_MS,
    ) = HistoryEntryEntity(categoryId = categoryId, date = date, text = text, savedAt = savedAt)

    // --- Fakes ---

    class FakeHistoryDao : HistoryDao {
        val inserted = mutableListOf<HistoryEntryEntity>()
        var pruned = false

        override suspend fun insertHistory(entry: HistoryEntryEntity) {
            inserted.add(entry)
        }

        override fun observeHistoryForDate(date: LocalDate): Flow<List<HistoryEntryEntity>> =
            flowOf(emptyList())

        override fun observeHistoryForDateAndCategory(
            date: LocalDate,
            categoryId: Long,
        ): Flow<List<HistoryEntryEntity>> = flowOf(emptyList())

        override fun observeAllCategories(): Flow<List<HistoryCategoryEntity>> = flowOf(emptyList())

        override suspend fun pruneOld(cutoff: Long) {
            pruned = true
        }

        override suspend fun clearAll() {}

        override suspend fun getHistoryCount(): Int = 0

        override suspend fun upsertCategory(name: HistoryCategoryEntity) {}
    }
}
