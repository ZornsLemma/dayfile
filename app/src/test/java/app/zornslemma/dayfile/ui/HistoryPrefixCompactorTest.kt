package app.zornslemma.dayfile.ui

import org.junit.Assert
import org.junit.Test

class HistoryPrefixCompactorTest {

    // Test data class mirroring the real HistoryString/HistoryDisplayItem structure
    private data class TestItem(val key: String, val id: Long, val savedAt: Long = 0)

    // Resolver that picks the "newer" item (higher savedAt, then higher id)
    private val newerWinsResolver = { a: TestItem, b: TestItem ->
        if (a.savedAt != b.savedAt) if (a.savedAt > b.savedAt) a else b
        else if (a.id > b.id) a else b
    }

    // Resolver that picks the "older" item (lower savedAt, then lower id)
    private val olderWinsResolver = { a: TestItem, b: TestItem ->
        if (a.savedAt != b.savedAt) if (a.savedAt < b.savedAt) a else b
        else if (a.id < b.id) a else b
    }

    // Resolver that always picks the second argument (verifies parameter order).
    // Since HashMap.merge calls remappingFunction(existing, new), this means
    // "last added wins" for same-key conflicts — effectively respecting insertion
    // order for same keys.
    private val pickSecondResolver = { _: TestItem, new: TestItem -> new }

    @Test
    fun prefixRejection_newIsPrefix() {
        val compactor =
            HistoryPrefixCompactor<TestItem>(keySelector = { it.key }, resolver = newerWinsResolver)
        val foo23 = TestItem("foo23", 1)
        val foo = TestItem("foo", 2)
        compactor.add(foo23)
        compactor.add(foo)

        val result = compactor.filter { true }
        Assert.assertEquals(listOf(foo23), result)
    }

    @Test
    fun prefixReplacement_existingIsPrefix() {
        val compactor =
            HistoryPrefixCompactor<TestItem>(keySelector = { it.key }, resolver = newerWinsResolver)
        val foo = TestItem("foo", 1)
        val foo23 = TestItem("foo23", 2)
        compactor.add(foo)
        compactor.add(foo23)

        val result = compactor.filter { true }
        Assert.assertEquals(listOf(foo23), result)
    }

    @Test
    fun prefixOrderIndependence() {
        val foo = TestItem("foo", 1)
        val foo23 = TestItem("foo23", 2)
        val bar = TestItem("bar", 3)
        val bar456 = TestItem("bar456", 4)
        val baz = TestItem("baz", 5)

        val items = listOf(foo, foo23, bar, bar456, baz)

        // Fixed permutations for determinism
        val orders =
            listOf(
                items,
                items.reversed(),
                listOf(items[2], items[0], items[4], items[1], items[3]),
                listOf(items[4], items[2], items[1], items[3], items[0]),
            )

        var expectedItems: Set<TestItem>? = null
        for (order in orders) {
            val compactor =
                HistoryPrefixCompactor<TestItem>(
                    keySelector = { it.key },
                    resolver = newerWinsResolver,
                )
            for (item in order) compactor.add(item)
            val kept = compactor.filter { true }.toSet()
            if (expectedItems == null) expectedItems = kept
            else Assert.assertEquals(expectedItems, kept)
        }
        // Expected: "foo23", "bar456", "baz" (longer strings win over their prefixes)
        Assert.assertEquals(setOf(foo23, bar456, baz), expectedItems)
    }

    @Test
    fun prefixChain() {
        // Chain of prefixes: "a" → "ab" → "abc" → "abcd" should leave only "abcd"
        val compactor =
            HistoryPrefixCompactor<TestItem>(keySelector = { it.key }, resolver = newerWinsResolver)
        val a = TestItem("a", 1)
        val ab = TestItem("ab", 2)
        val abc = TestItem("abc", 3)
        val abcd = TestItem("abcd", 4)
        compactor.add(a)
        compactor.add(ab)
        compactor.add(abc)
        compactor.add(abcd)

        val result = compactor.filter { true }
        Assert.assertEquals(listOf(abcd), result)
    }

    @Test
    fun sameKeyResolution_newerWins() {
        val compactor =
            HistoryPrefixCompactor<TestItem>(keySelector = { it.key }, resolver = newerWinsResolver)
        val older = TestItem("foo", 1, savedAt = 100)
        val newer = TestItem("foo", 2, savedAt = 200)
        compactor.add(older)
        compactor.add(newer)

        val result = compactor.filter { true }
        Assert.assertEquals(listOf(newer), result)
    }

    @Test
    fun sameKeyResolution_olderWins() {
        val compactor =
            HistoryPrefixCompactor<TestItem>(keySelector = { it.key }, resolver = olderWinsResolver)
        val older = TestItem("foo", 1, savedAt = 100)
        val newer = TestItem("foo", 2, savedAt = 200)
        compactor.add(older)
        compactor.add(newer)

        val result = compactor.filter { true }
        Assert.assertEquals(listOf(older), result)
    }

    @Test
    fun sameKeyResolution_orderSensitiveResolver() {
        val compactor =
            HistoryPrefixCompactor<TestItem>(
                keySelector = { it.key },
                resolver = pickSecondResolver,
            )
        val first = TestItem("foo", 1)
        val second = TestItem("foo", 2)
        compactor.add(first)
        compactor.add(second)

        val result = compactor.filter { true }
        Assert.assertEquals(listOf(second), result)
    }

    @Test
    fun multipleSameKeyAdditions() {
        // Three additions with same key, resolver should be invoked for each conflict
        val compactor =
            HistoryPrefixCompactor<TestItem>(keySelector = { it.key }, resolver = newerWinsResolver)
        val first = TestItem("foo", 1, savedAt = 100)
        val second = TestItem("foo", 2, savedAt = 200)
        val third = TestItem("foo", 3, savedAt = 300)
        compactor.add(first)
        compactor.add(second)
        compactor.add(third)

        val result = compactor.filter { true }
        Assert.assertEquals(listOf(third), result)
    }

    @Test
    fun divergentNonPrefixCoexistence() {
        val compactor =
            HistoryPrefixCompactor<TestItem>(keySelector = { it.key }, resolver = newerWinsResolver)
        val foo23 = TestItem("foo23", 1)
        val bar45 = TestItem("bar45", 2)
        compactor.add(foo23)
        compactor.add(bar45)

        val result = compactor.filter { true }
        Assert.assertEquals(setOf(foo23, bar45), result.toSet())
    }

    @Test
    fun filterByIdThreshold() {
        val compactor =
            HistoryPrefixCompactor<TestItem>(keySelector = { it.key }, resolver = newerWinsResolver)
        val a = TestItem("a", 1)
        val b = TestItem("b", 5)
        val c = TestItem("c", 10)
        compactor.add(a)
        compactor.add(b)
        compactor.add(c)

        val result = compactor.filter { it.id > 5 }
        Assert.assertEquals(listOf(c), result)
    }

    @Test
    fun filterByKeyContent() {
        val compactor =
            HistoryPrefixCompactor<TestItem>(keySelector = { it.key }, resolver = newerWinsResolver)
        val foo1 = TestItem("foo1", 1)
        val bar2 = TestItem("bar2", 2)
        compactor.add(foo1)
        compactor.add(bar2)

        val result = compactor.filter { it.key.contains("foo") }
        Assert.assertEquals(listOf(foo1), result)
    }

    @Test
    fun emptyCompactor() {
        val compactor =
            HistoryPrefixCompactor<TestItem>(keySelector = { it.key }, resolver = newerWinsResolver)

        val result = compactor.filter { true }
        Assert.assertTrue(result.isEmpty())
    }

    @Test
    fun filterReturnsEmpty() {
        // Predicate filters out all items
        val compactor =
            HistoryPrefixCompactor<TestItem>(keySelector = { it.key }, resolver = newerWinsResolver)
        val a = TestItem("a", 1)
        val b = TestItem("b", 2)
        compactor.add(a)
        compactor.add(b)

        val result = compactor.filter { it.id > 100 }
        Assert.assertTrue(result.isEmpty())
    }

    @Test
    fun caseSensitivity() {
        val compactor =
            HistoryPrefixCompactor<TestItem>(keySelector = { it.key }, resolver = newerWinsResolver)
        val foo = TestItem("foo", 1)
        val fooUpper = TestItem("FOO", 2)
        compactor.add(foo)
        compactor.add(fooUpper)

        val result = compactor.filter { true }
        Assert.assertEquals(setOf(foo, fooUpper), result.toSet())
    }

    @Test
    fun emptyStringEdgeCase() {
        // Empty string added first, then non-empty
        var compactor =
            HistoryPrefixCompactor<TestItem>(keySelector = { it.key }, resolver = newerWinsResolver)
        val empty = TestItem("", 1)
        val foo = TestItem("foo", 2)
        compactor.add(empty)
        compactor.add(foo)
        var result = compactor.filter { true }
        Assert.assertEquals(listOf(foo), result)

        // Non-empty added first, then empty string
        compactor =
            HistoryPrefixCompactor<TestItem>(keySelector = { it.key }, resolver = newerWinsResolver)
        compactor.add(foo)
        compactor.add(empty)
        result = compactor.filter { true }
        Assert.assertEquals(listOf(foo), result)
    }

    @Test
    fun prefixReplacementThenSameKeyResolution() {
        // Prefix replacement followed by same-key conflict should invoke resolver
        val compactor =
            HistoryPrefixCompactor<TestItem>(keySelector = { it.key }, resolver = newerWinsResolver)
        val foo = TestItem("foo", 1)
        val foo23_first = TestItem("foo23", 2, savedAt = 100)
        val foo23_second = TestItem("foo23", 3, savedAt = 200)
        compactor.add(foo)
        compactor.add(foo23_first) // replaces "foo"
        compactor.add(foo23_second) // same key, resolver picks newer (savedAt=200)

        val result = compactor.filter { true }
        Assert.assertEquals(listOf(foo23_second), result)
    }
}
