package app.zornslemma.dayfile.data

import java.io.ByteArrayOutputStream
import java.time.LocalDate
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CsvExportHelperTest {

    private val categories =
        listOf(
            CategoryEntity(id = 1, name = "Diet", ordering = 1, enabled = true),
            CategoryEntity(id = 2, name = "Money", ordering = 0, enabled = true),
            CategoryEntity(id = 3, name = "Hidden", ordering = 2, enabled = false),
        )

    @Test
    fun `exports headers and rows sorted by date then category order`() {
        val entries =
            listOf(
                EntryEntity(categoryId = 1, date = LocalDate.parse("2026-01-02"), text = "salad"),
                EntryEntity(categoryId = 2, date = LocalDate.parse("2026-01-01"), text = "4.50"),
                EntryEntity(categoryId = 2, date = LocalDate.parse("2026-01-02"), text = "10.00"),
            )
        val out = ByteArrayOutputStream()
        CsvExportHelper.export(out, categories, entries, writeBom = false)
        val csv = out.toString("UTF-8")
        val lines = csv.split("\r\n")
        assertEquals("Date,Category,Category Order,Entry", lines[0])
        // 2026-01-01 Money first
        assertEquals("2026-01-01,Money,0,4.50", lines[1])
        // 2026-01-02 Money then Diet (order 0 < 1)
        assertEquals("2026-01-02,Money,0,10.00", lines[2])
        assertEquals("2026-01-02,Diet,1,salad", lines[3])
        assertEquals("", lines[4]) // trailing CRLF yields empty last element
    }

    @Test
    fun `quotes fields with comma newline and quote`() {
        val entries =
            listOf(
                EntryEntity(
                    categoryId = 1,
                    date = LocalDate.parse("2026-01-01"),
                    text = "a,b\nc\"d",
                )
            )
        val out = ByteArrayOutputStream()
        CsvExportHelper.export(out, categories, entries, writeBom = false)
        val csv = out.toString("UTF-8")
        assertTrue(csv.contains("\"a,b\nc\"\"d\""))
    }

    // The helper performs no row filtering of its own: selection is the caller's policy
    // (selectExportData), formatting is the helper's. Passing a blank entry through
    // unfiltered pins the helper's half of that division of labour - the whitespace-only
    // value reaches the CSV verbatim, unquoted (it contains none of quoteField's trigger
    // characters).
    @Test
    fun `passes blank entries through unfiltered`() {
        val entries =
            listOf(
                EntryEntity(categoryId = 1, date = LocalDate.parse("2026-01-01"), text = "   "),
                EntryEntity(categoryId = 2, date = LocalDate.parse("2026-01-01"), text = "valid"),
            )
        val out = ByteArrayOutputStream()
        CsvExportHelper.export(out, categories, entries, writeBom = false)
        val csv = out.toString("UTF-8")
        val lines = csv.split("\r\n")
        // Money (ordering 0) sorts before Diet (ordering 1); the whitespace entry is
        // emitted verbatim, not filtered and not quoted.
        assertEquals("2026-01-01,Money,0,valid", lines[1])
        assertEquals("2026-01-01,Diet,1,   ", lines[2])
    }

    @Test
    fun `defensively drops entries whose category is absent`() {
        // The app's own caller (selectExportData) now guarantees every entry's category is
        // present, so this guard is unreachable through the normal pipeline. It remains
        // load-bearing as a safety net - the sort key dereferences the category lookup with
        // !! - and this test pins that a misbehaving caller gets silent omission of the
        // orphan rows rather than a crash. The row-inclusion policy (a row appears iff its
        // entry text is non-blank AND its category is among the selected categories) is owned
        // by the caller, not by this helper.
        val entries =
            listOf(
                EntryEntity(categoryId = 1, date = LocalDate.parse("2026-01-01"), text = "known"),
                EntryEntity(categoryId = 99, date = LocalDate.parse("2026-01-01"), text = "orphan"),
            )
        val out = ByteArrayOutputStream()
        CsvExportHelper.export(out, categories, entries, writeBom = false)
        val csv = out.toString("UTF-8")
        assertTrue(csv.contains("known"))
        assertFalse(csv.contains("orphan"))
    }

    @Test
    fun `writes BOM when requested`() {
        val entries =
            listOf(EntryEntity(categoryId = 1, date = LocalDate.parse("2026-01-01"), text = "x"))
        val out = ByteArrayOutputStream()
        CsvExportHelper.export(out, categories, entries, writeBom = true)
        val bytes = out.toByteArray()
        assertArrayEquals(
            byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()),
            bytes.copyOfRange(0, 3),
        )
    }

    @Test
    fun `no BOM when not requested`() {
        val entries =
            listOf(EntryEntity(categoryId = 1, date = LocalDate.parse("2026-01-01"), text = "x"))
        val out = ByteArrayOutputStream()
        CsvExportHelper.export(out, categories, entries, writeBom = false)
        val bytes = out.toByteArray()
        assertFalse(
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        )
    }
}
