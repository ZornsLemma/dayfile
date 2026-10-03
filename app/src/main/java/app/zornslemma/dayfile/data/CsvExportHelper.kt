package app.zornslemma.dayfile.data

import java.io.BufferedWriter
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

/**
 * Exports entries to a CSV file in RFC 4180 style for maximum compatibility. Note that this
 * specifies \r\n line terminators, so we respect that.
 *
 * The CSV uses fixed English headers (Date, Category, Category Order, Entry) and is sorted by date
 * then category ordering. Blank entries are excluded by the caller, as are entries whose category
 * is not among the supplied categories; entries referencing an absent category are additionally
 * dropped here as a defensive measure, since the sort key needs the category lookup. Writing is
 * streamed to the provided OutputStream; a UTF-8 BOM is written first if [writeBom] is true.
 */
object CsvExportHelper {

    private val HEADERS = arrayOf("Date", "Category", "Category Order", "Entry")
    private val BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    /**
     * Writes the CSV to [output].
     *
     * @param output stream to write to (e.g. from Storage Access Framework)
     * @param categories all categories (including disabled if export requested)
     * @param entries all entries (already filtered for blanks and non-selected categories by
     *   caller)
     * @param writeBom if true, prepend a UTF-8 BOM
     */
    fun export(
        output: OutputStream,
        categories: List<CategoryEntity>,
        entries: List<EntryEntity>,
        writeBom: Boolean,
    ) {
        if (writeBom) output.write(BOM)

        val categoryById = categories.associateBy { it.id }
        val orderedEntries =
            entries
                .filter { categoryById.containsKey(it.categoryId) }
                .sortedWith(compareBy({ it.date }, { categoryById[it.categoryId]!!.ordering }))

        BufferedWriter(OutputStreamWriter(output, StandardCharsets.UTF_8)).use { writer ->
            writer.write(HEADERS.joinToString(",") { quoteField(it) })
            writer.write("\r\n")

            for (entry in orderedEntries) {
                val cat = categoryById[entry.categoryId]!!
                val row =
                    listOf(entry.date.toString(), cat.name, cat.ordering.toString(), entry.text)
                        .joinToString(",") { quoteField(it) }
                writer.write(row)
                writer.write("\r\n")
            }
        }
    }

    private fun quoteField(value: String): String {
        return if (
            value.contains(',') ||
                value.contains('\n') ||
                value.contains('\r') ||
                value.contains('"')
        ) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }
    }
}
