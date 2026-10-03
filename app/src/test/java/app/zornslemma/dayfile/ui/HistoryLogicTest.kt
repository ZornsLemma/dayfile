package app.zornslemma.dayfile.ui

import app.zornslemma.dayfile.data.CategoryEntity
import app.zornslemma.dayfile.data.HistoryCategoryEntity
import app.zornslemma.dayfile.data.HistoryEntryEntity
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// JVM-level tests for the history display policy living in HistoryLogic: prefix compaction
// partitioned per category, the dropdown option merge, option visibility (which must agree with
// filter resolution so the dropdown can always name the filter the ViewModel is applying),
// effective-filter resolution (whose unresolvable case is the ViewModel's self-heal trigger) and
// raw-row projection. HPC's own antichain mechanics are covered by HistoryPrefixCompactorTest.
class HistoryLogicTest {

    // Raw names throughout: the "(deleted, ID n)" marker is applied at render time
    // (HistoryScreen.deletedCategoryLabel), so the logic layer never sees or produces it.
    private fun options() =
        listOf(CategoryOption(1, "Diet", false), CategoryOption(9, "Zeta", true))

    @Test
    fun `collapses typing prefix chain within same category`() {
        val items =
            listOf(
                HistoryDisplayItem(
                    id = 1,
                    text = "Phon",
                    savedAt = 100,
                    categoryId = 1,
                    categoryName = "Diet",
                ),
                HistoryDisplayItem(
                    id = 2,
                    text = "Phone",
                    savedAt = 200,
                    categoryId = 1,
                    categoryName = "Diet",
                ),
                HistoryDisplayItem(
                    id = 3,
                    text = "Phone num",
                    savedAt = 300,
                    categoryId = 1,
                    categoryName = "Diet",
                ),
            )
        // Whole-list equality pins the complete surviving snapshot (newest-wins), not just
        // the collapsed text.
        assertEquals(
            listOf(
                HistoryDisplayItem(
                    id = 3,
                    text = "Phone num",
                    savedAt = 300,
                    categoryId = 1,
                    categoryName = "Diet",
                )
            ),
            HistoryLogic.collapsePrefixes(items),
        )
    }

    @Test
    fun `keeps divergent edits within same category`() {
        val items =
            listOf(
                HistoryDisplayItem(
                    id = 1,
                    text = "Phone number is 042332 21",
                    savedAt = 100,
                    categoryId = 1,
                    categoryName = "Diet",
                ),
                HistoryDisplayItem(
                    id = 2,
                    text = "Phone number is X",
                    savedAt = 200,
                    categoryId = 1,
                    categoryName = "Diet",
                ),
            )
        // Neither text is a prefix of the other, so both survive; whole-list equality also
        // pins newest-first ordering within the category.
        assertEquals(
            listOf(
                HistoryDisplayItem(
                    id = 2,
                    text = "Phone number is X",
                    savedAt = 200,
                    categoryId = 1,
                    categoryName = "Diet",
                ),
                HistoryDisplayItem(
                    id = 1,
                    text = "Phone number is 042332 21",
                    savedAt = 100,
                    categoryId = 1,
                    categoryName = "Diet",
                ),
            ),
            HistoryLogic.collapsePrefixes(items),
        )
    }

    @Test
    fun `does not collapse across different categories even with colliding display names`() {
        // Guards a real collision: a live category literally named "Exercise (deleted, ID 4)"
        // and a deleted category ID 4 named "Exercise" both render with the display name
        // "Exercise (deleted, ID 4)". Grouping by display name would incorrectly collapse them
        // together. Grouping by categoryId keeps them separate.
        val items =
            listOf(
                HistoryDisplayItem(
                    id = 1,
                    text = "A",
                    savedAt = 100,
                    categoryId = 99,
                    categoryName = "Exercise (deleted, ID 4)",
                ),
                HistoryDisplayItem(
                    id = 2,
                    text = "AB",
                    savedAt = 200,
                    categoryId = 4,
                    categoryName = "Exercise (deleted, ID 4)",
                ),
            )
        // Whole-list equality pins both the non-collapse and the global newest-first order.
        assertEquals(
            listOf(
                HistoryDisplayItem(
                    id = 2,
                    text = "AB",
                    savedAt = 200,
                    categoryId = 4,
                    categoryName = "Exercise (deleted, ID 4)",
                ),
                HistoryDisplayItem(
                    id = 1,
                    text = "A",
                    savedAt = 100,
                    categoryId = 99,
                    categoryName = "Exercise (deleted, ID 4)",
                ),
            ),
            HistoryLogic.collapsePrefixes(items),
        )
    }

    @Test
    fun `collapses deletion prefix chain within same category`() {
        val items =
            listOf(
                HistoryDisplayItem(
                    id = 1,
                    text = "Phone number is 235",
                    savedAt = 100,
                    categoryId = 1,
                    categoryName = "Diet",
                ),
                HistoryDisplayItem(
                    id = 2,
                    text = "Phone",
                    savedAt = 200,
                    categoryId = 1,
                    categoryName = "Diet",
                ),
            )
        // The longer snapshot arrives first and the shorter later: the shorter one is
        // discarded as a prefix of the stored longer one, so the OLDER snapshot survives.
        assertEquals(
            listOf(
                HistoryDisplayItem(
                    id = 1,
                    text = "Phone number is 235",
                    savedAt = 100,
                    categoryId = 1,
                    categoryName = "Diet",
                )
            ),
            HistoryLogic.collapsePrefixes(items),
        )
    }

    @Test
    fun `same-text collisions resolve newest-wins regardless of insertion order`() {
        val items =
            listOf(
                HistoryDisplayItem(
                    id = 2,
                    text = "same text",
                    savedAt = 200,
                    categoryId = 1,
                    categoryName = "Diet",
                ),
                HistoryDisplayItem(
                    id = 1,
                    text = "same text",
                    savedAt = 100,
                    categoryId = 1,
                    categoryName = "Diet",
                ),
            )
        // The newer snapshot arrives FIRST, so a naive last-write-wins would keep the older
        // one; only the savedAt resolver yields this expectation.
        assertEquals(
            listOf(
                HistoryDisplayItem(
                    id = 2,
                    text = "same text",
                    savedAt = 200,
                    categoryId = 1,
                    categoryName = "Diet",
                )
            ),
            HistoryLogic.collapsePrefixes(items),
        )
    }

    @Test
    fun `returns survivors globally newest-first across categories`() {
        val items =
            listOf(
                HistoryDisplayItem(
                    id = 1,
                    text = "alpha",
                    savedAt = 100,
                    categoryId = 1,
                    categoryName = "Diet",
                ),
                HistoryDisplayItem(
                    id = 2,
                    text = "beta",
                    savedAt = 300,
                    categoryId = 2,
                    categoryName = "Money",
                ),
                HistoryDisplayItem(
                    id = 3,
                    text = "alphabet",
                    savedAt = 200,
                    categoryId = 1,
                    categoryName = "Diet",
                ),
            )
        // "alpha" is superseded by "alphabet" within category 1 (and must never be compared
        // against category 2's "beta"); the survivors interleave across categories purely by
        // savedAt.
        assertEquals(
            listOf(
                HistoryDisplayItem(
                    id = 2,
                    text = "beta",
                    savedAt = 300,
                    categoryId = 2,
                    categoryName = "Money",
                ),
                HistoryDisplayItem(
                    id = 3,
                    text = "alphabet",
                    savedAt = 200,
                    categoryId = 1,
                    categoryName = "Diet",
                ),
            ),
            HistoryLogic.collapsePrefixes(items),
        )
    }

    // --- buildCategoryOptions ---

    @Test
    fun `buildCategoryOptions lists enabled live categories in ordering order`() {
        val live =
            listOf(
                CategoryEntity(id = 1, name = "Diet", ordering = 1, enabled = true),
                CategoryEntity(id = 2, name = "Money", ordering = 0, enabled = true),
                CategoryEntity(id = 3, name = "Hidden", ordering = 2, enabled = false),
            )
        assertEquals(
            listOf(CategoryOption(2, "Money", false), CategoryOption(1, "Diet", false)),
            HistoryLogic.buildCategoryOptions(live, emptyList()),
        )
    }

    @Test
    fun `buildCategoryOptions appends archive-only categories with raw names sorted by id`() {
        val live = listOf(CategoryEntity(id = 1, name = "Diet", ordering = 0, enabled = true))
        val archived =
            listOf(
                HistoryCategoryEntity(id = 9, name = "Zeta"),
                HistoryCategoryEntity(id = 5, name = "Alpha"),
            )
        assertEquals(
            listOf(
                CategoryOption(1, "Diet", false),
                CategoryOption(5, "Alpha", true),
                CategoryOption(9, "Zeta", true),
            ),
            HistoryLogic.buildCategoryOptions(live, archived),
        )
    }

    @Test
    fun `buildCategoryOptions does not reintroduce live-but-disabled categories from the archive`() {
        val live = listOf(CategoryEntity(id = 3, name = "Retired", ordering = 0, enabled = false))
        val archived = listOf(HistoryCategoryEntity(id = 3, name = "Retired"))
        assertEquals(emptyList<CategoryOption>(), HistoryLogic.buildCategoryOptions(live, archived))
    }

    // --- visibleOptions ---

    @Test
    fun `visibleOptions hides deleted categories unless includeDeleted is set`() {
        assertEquals(
            listOf(CategoryOption(1, "Diet", false)),
            HistoryLogic.visibleOptions(options(), includeDeleted = false),
        )
    }

    @Test
    fun `visibleOptions shows everything in order when includeDeleted is set`() {
        // Whole-list equality also pins that visibility filtering never re-orders options.
        assertEquals(options(), HistoryLogic.visibleOptions(options(), includeDeleted = true))
    }

    // --- resolveEffectiveFilter ---

    @Test
    fun `null raw filter resolves to null`() {
        assertNull(HistoryLogic.resolveEffectiveFilter(null, options(), includeDeleted = false))
    }

    @Test
    fun `live category filter always resolves`() {
        assertEquals(1L, HistoryLogic.resolveEffectiveFilter(1L, options(), includeDeleted = false))
    }

    @Test
    fun `deleted category filter resolves only when included`() {
        assertNull(HistoryLogic.resolveEffectiveFilter(9L, options(), includeDeleted = false))
        assertEquals(9L, HistoryLogic.resolveEffectiveFilter(9L, options(), includeDeleted = true))
    }

    @Test
    fun `unknown category filter never resolves`() {
        assertNull(HistoryLogic.resolveEffectiveFilter(42L, options(), includeDeleted = true))
    }

    // --- mapEntriesToDisplayItems ---

    @Test
    fun `entries map to display items carrying the category name`() {
        val entries =
            listOf(
                HistoryEntryEntity(
                    id = 1,
                    categoryId = 1,
                    date = LocalDate.parse("2026-08-01"),
                    text = "salad",
                    savedAt = 100,
                )
            )
        assertEquals(
            listOf(
                HistoryDisplayItem(
                    id = 1,
                    text = "salad",
                    savedAt = 100,
                    categoryId = 1,
                    categoryName = "Diet",
                )
            ),
            HistoryLogic.mapEntriesToDisplayItems(entries, options(), includeDeleted = false),
        )
    }

    @Test
    fun `orphaned entries are dropped even when deleted categories are included`() {
        val entries =
            listOf(
                HistoryEntryEntity(
                    id = 1,
                    categoryId = 999,
                    date = LocalDate.parse("2026-08-01"),
                    text = "ghost",
                    savedAt = 100,
                )
            )
        assertEquals(
            emptyList<HistoryDisplayItem>(),
            HistoryLogic.mapEntriesToDisplayItems(entries, options(), includeDeleted = true),
        )
    }

    @Test
    fun `deleted category entries appear only when included`() {
        val entries =
            listOf(
                HistoryEntryEntity(
                    id = 1,
                    categoryId = 9,
                    date = LocalDate.parse("2026-08-01"),
                    text = "old",
                    savedAt = 100,
                )
            )
        assertEquals(
            emptyList<HistoryDisplayItem>(),
            HistoryLogic.mapEntriesToDisplayItems(entries, options(), includeDeleted = false),
        )
        assertEquals(
            listOf(
                HistoryDisplayItem(
                    id = 1,
                    text = "old",
                    savedAt = 100,
                    categoryId = 9,
                    categoryName = "Zeta",
                    categoryDeleted = true,
                )
            ),
            HistoryLogic.mapEntriesToDisplayItems(entries, options(), includeDeleted = true),
        )
    }
}
