package app.zornslemma.dayfile.ui

import app.zornslemma.dayfile.data.CategoryEntity
import app.zornslemma.dayfile.data.EntryEntity
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

// Pins the export selection policy implemented by selectExportData(): which data is handed to
// CsvExportHelper (SettingsViewModel.export() calls selectExportData first, then hands the
// result to CsvExportHelper). CSV formatting/sorting itself is covered by CsvExportHelperTest;
// these tests exist so the caller-side decisions (disabled-category inclusion, blank-entry
// dropping, orphan-entry dropping alongside their excluded categories, order preservation)
// cannot silently drift.
class SettingsExportSelectionTest {

    private val categories =
        listOf(
            CategoryEntity(id = 1, name = "Money", ordering = 0, enabled = true),
            CategoryEntity(id = 2, name = "Diet", ordering = 1, enabled = true),
            CategoryEntity(id = 3, name = "Retired", ordering = 2, enabled = false),
        )

    private fun entry(categoryId: Long, text: String) =
        EntryEntity(categoryId = categoryId, date = LocalDate.parse("2026-01-01"), text = text)

    @Test
    fun `excludes disabled categories by default`() {
        val entries = listOf(entry(1, "salad"))
        val (selectedCategories, selectedEntries) =
            selectExportData(categories, entries, includeDisabled = false)
        assertEquals(listOf(categories[0], categories[1]), selectedCategories)
        assertEquals(entries, selectedEntries)
    }

    @Test
    fun `includes disabled categories when requested`() {
        val entries = listOf(entry(3, "old note"))
        val (selectedCategories, selectedEntries) =
            selectExportData(categories, entries, includeDisabled = true)
        assertEquals(categories, selectedCategories)
        assertEquals(entries, selectedEntries)
    }

    @Test
    fun `drops entries belonging to excluded categories`() {
        // Category 3 is disabled, so with the default flag its entry must not reach the
        // helper. This drop used to happen implicitly inside CsvExportHelper (as a side effect
        // of resolving category names for sorting); owning it here makes the row-inclusion
        // policy complete and visible at the layer responsible for it.
        val entries = listOf(entry(1, "kept"), entry(3, "dropped with its disabled category"))
        val (_, selected) = selectExportData(categories, entries, includeDisabled = false)
        assertEquals(listOf(entries[0]), selected)

        // Including disabled categories brings their entries back.
        val (_, selectedAll) = selectExportData(categories, entries, includeDisabled = true)
        assertEquals(entries, selectedAll)
    }

    @Test
    fun `drops blank entries regardless of the flag`() {
        val entries = listOf(entry(1, "   "), entry(2, "valid"))
        val (_, blankFiltered) = selectExportData(categories, entries, includeDisabled = false)
        assertEquals(listOf(entries[1]), blankFiltered)
        val (_, blankFilteredIncluded) =
            selectExportData(categories, entries, includeDisabled = true)
        assertEquals(listOf(entries[1]), blankFilteredIncluded)
    }

    @Test
    fun `preserves input ordering instead of sorting`() {
        // Deliberately unsorted fixtures: sorting belongs to CsvExportHelper downstream, so
        // selection must filter without reordering either list.
        val unsortedCategories =
            listOf(
                CategoryEntity(id = 1, name = "Diet", ordering = 5, enabled = true),
                CategoryEntity(id = 2, name = "Money", ordering = 0, enabled = true),
                CategoryEntity(id = 3, name = "Hidden", ordering = 9, enabled = false),
            )
        val unsortedEntries = listOf(entry(2, "later row"), entry(1, "earlier row"))
        val (selectedCategories, selectedEntries) =
            selectExportData(unsortedCategories, unsortedEntries, includeDisabled = false)
        assertEquals(listOf(unsortedCategories[0], unsortedCategories[1]), selectedCategories)
        assertEquals(unsortedEntries, selectedEntries)
    }

    @Test
    fun `empty inputs pass through untouched`() {
        val (selectedCategories, selectedEntries) =
            selectExportData(emptyList(), emptyList(), includeDisabled = true)
        assertEquals(emptyList<CategoryEntity>(), selectedCategories)
        assertEquals(emptyList<EntryEntity>(), selectedEntries)
    }
}
