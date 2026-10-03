package app.zornslemma.dayfile.ui

import android.util.Log
import app.zornslemma.dayfile.data.HistoryDao
import app.zornslemma.dayfile.data.HistoryEntryEntity
import java.time.LocalDate
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

// This class encapsulates the "pare down the user's live input into something we want to store in
// the history database" logic, to avoid polluting HomeViewModel with it directly. We "pre-filter"
// the history in a way which is compatible with the final filtering performed in the history view
// screen, in order to reduce the size and quantity of database writes without removing anything
// that would make it through to the final user visible results anyway. Because we don't always
// have the full state (most obviously when we are first created and our internal "cache" is empty),
// we may write things to the history table that are redundant, but that's fine.

// This class breaks things down by (date, category) rather than just category simply because the
// debouncing and lack of explicit flushing means that we may end up with history tracked across
// different dates as the user navigates.

// As noted in the comment at the top of HomeViewModel, history is to guard against accidental edits
// caused by (e.g.) fumbling the phone. We don't attempt to also protect against the sheer bad luck
// of this happening and then the app getting backgrounded and Android immediately killing it before
// the pending history can be preserved via SavedStateHandle for later writing to the database. This
// would likely be possible, but given the way we try to pre-filter the history in chunks using
// de-bouncing, it would potentially be complex and error-prone and we just don't try at the moment,
// and maybe never.

// The internal cache here will grow unboundedly over time. We don't care. In reality it is not
// going to be of a significant size, it has some value in improving our de-duplication of history,
// and trying to flush or prune it would add extra complexity.

// savedAt is taken from [clock] at the moment each offer is recorded. The clock is injectable so
// tests can pin times deterministically. Note that this is deliberately a raw epoch-millis supplier
// rather than something like HomeViewModel's LocalDateTime clock: savedAt is only ever used for
// ordering and retention cutoffs, so it carries no timezone semantics.

class HistoryCaptureHelper(
    private val historyDao: HistoryDao,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class HistoryString(val id: Long, val text: String, val savedAt: Long)

    // newEntryTrigger is used merely to trigger the debounce processing - it doesn't convey any
    // actual data itself.
    private val newEntryTrigger = MutableSharedFlow<Unit>(extraBufferCapacity = 64)

    // HistoryString has an ID which is guaranteed unique and monotonically increasing. This is
    // present so we can use it to decide what needs writing to the database. We could almost use
    // savedAt for this, but it only has millisecond resolution and might lead to us discarding
    // otherwise useful history writes.
    private val initialHistoryStringId: Long = 0
    private var historyStringId: Long = initialHistoryStringId
    private var lastWrittenHistoryStringId = initialHistoryStringId - 1

    private val compactHistoryByKey =
        mutableMapOf<Pair<LocalDate, Long>, HistoryPrefixCompactor<HistoryString>>()

    init {
        newEntryTrigger
            .debounce(SAVE_DEBOUNCE_MS)
            .onEach {
                // We use toList() here to avoid a corner case error where we suspend inside
                // writeHistorySnapshot() and a previously unseen (date, category) key gets inserted
                // into the map as the user interacts with the UI, which would invalidate the
                // iterator if we were iterating directly over the map.
                //
                // lastWrittenHistoryStringId is deliberately left alone until every key has been
                // visited. Ids are handed out globally but each key has its own compactor, so a key
                // visited later can still be holding a pending id *below* one an earlier key has
                // just written - that is exactly what happens when the user edits two fields in
                // one burst and the second edit is the shorter of a prefix chain. Advancing the
                // watermark as we went would filter that pending snapshot out and drop it
                // silently. Reading it inside the filter below is therefore safe only because the
                // sole assignment is after this loop; moving it back inside would reintroduce the
                // bug. (Pinned by HistoryCaptureHelperTest's "interleaved offers across keys all
                // reach the database".)
                var highWatermark = lastWrittenHistoryStringId
                compactHistoryByKey.toList().forEach { (key, compactHistory) ->
                    // Note that filter() does not guarantee anything about the order of its
                    // elements. It doesn't really matter, but for neatness we sort the results so
                    // older history entries are inserted first and therefore get lower database
                    // primary key IDs. We might want to rely on this later on, and it also makes
                    // a naive view of the database table (sorted by primary key ID) more readable
                    // when debugging.
                    val list =
                        compactHistory
                            .filter { it.id > lastWrittenHistoryStringId }
                            .sortedBy { it.id }
                    for (historyString in list) {
                        writeHistorySnapshot(
                            date = key.first,
                            categoryId = key.second,
                            text = historyString.text,
                            savedAt = historyString.savedAt,
                        )
                        // A normal insert failure is deliberately treated as best effort: the
                        // flush's watermark advances past this snapshot once the flush completes,
                        // so the failed snapshot is abandoned rather than retried. History is a
                        // recovery safety net, not a second source of user entries, and an
                        // automatic retry policy for a full disk or unavailable database would be
                        // a separate durability decision. Cancellation is different and must
                        // escape writeHistorySnapshot so the ViewModel scope is never swallowed.
                        highWatermark = max(highWatermark, historyString.id)
                    }
                }
                lastWrittenHistoryStringId = highWatermark
            }
            .launchIn(scope)
    }

    fun offerEntryText(date: LocalDate, categoryId: Long, text: String) {
        if (text.isBlank()) {
            // We don't want to be populating an otherwise empty history compactor with a blank
            // entry.
            return
        }
        // Leading/trailing whitespace in an offer is an intermediate keystroke state rather
        // than part of the entry: a user who types a space and then goes on to type a letter is
        // one entry caught mid-edit, not two. If we stored the whitespace, "30 " and "30m" would
        // not be prefix-related ("30m" does not start with "30 "), so both would survive as
        // visibly distinct history rows. Storing the trimmed text keeps such variants
        // prefix-related, which means the compactor here collapses them exactly as it collapses
        // any other typing burst, and the display-time collapse in HistoryLogic then hides the
        // earlier row because the two stored texts genuinely are prefix-related - so no change
        // was needed on the display side.
        //
        // We trim here rather than in saveEntry so that the entry table keeps holding exactly what
        // the user last saw, which is the field/database contract documented on HomeScreen.kt's
        // rememberEntryFieldState. Note also that we trim the snapshot we store rather than only
        // the compactor's key: that would collapse the variants equally well, but would leave the
        // raw whitespace in the row for the user to copy back.
        val trimmedText = text.trim()
        val key = Pair(date, categoryId)
        val compactHistory =
            compactHistoryByKey.getOrPut(key) {
                HistoryPrefixCompactor(
                    keySelector = { it.text },
                    resolver = { a, b -> if (a.id > b.id) a else b },
                )
            }
        compactHistory.add(
            HistoryString(id = historyStringId++, text = trimmedText, savedAt = clock())
        )
        newEntryTrigger.tryEmit(Unit)
    }

    private suspend fun writeHistorySnapshot(
        date: LocalDate,
        categoryId: Long,
        text: String,
        savedAt: Long,
    ) {
        // Offers are already rejected when blank and are stored trimmed, so this cannot fire
        // today. We keep it as a cheap guard on the one call which actually writes to the database.
        if (text.isBlank()) return
        try {
            historyDao.insertHistory(
                HistoryEntryEntity(
                    categoryId = categoryId,
                    date = date,
                    text = text,
                    savedAt = savedAt,
                )
            )
        } catch (e: CancellationException) {
            // Cancellation is structured concurrency, not a history database failure. Swallowing
            // it here would keep this debounced pipeline running after its owning ViewModel has
            // been cleared and would log expected teardown as an insertion error.
            throw e
        } catch (e: Exception) {
            // History is a background safety net, not a user-facing feature: a failed write must
            // not break the debounced pipeline or surface to the user. The only place the
            // failure survives is logcat, so we log it here rather than swallowing silently -
            // a history write failing in the wild (disk full, DB lock, corruption) is exactly
            // the "I can't reproduce it but I wish there had been logging" scenario.
            Log.e(TAG, "Failed to write history snapshot for category=$categoryId date=$date", e)
        }
    }

    companion object {
        private const val TAG = "HistoryCaptureHelper"
        private const val SAVE_DEBOUNCE_MS = 400L
    }
}
