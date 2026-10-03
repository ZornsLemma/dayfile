package app.zornslemma.dayfile.ui

// I'm told this is effective an antichain using prefix partial order "a <= b iff b.startsWith(a)".
// I'm sure that statement contains so many errors the universe is too small to contain an
// explanation of all the ways in which it is wrong.
class HistoryPrefixCompactor<T : Any>(
    private val keySelector: (T) -> String,
    private val resolver: (a: T, b: T) -> T,
) {
    // This does not preserve insertion order. Ordering is determined entirely by resolver.
    private val storage = HashMap<String, T>()

    fun add(element: T) {
        val key = keySelector(element)

        if (storage.keys.any { it.startsWith(key) && it != key }) {
            return
        }

        val keysToRemove = storage.keys.filter { key.startsWith(it) && it != key }
        keysToRemove.forEach { storage.remove(it) }

        storage.merge(key, element) { existing, new -> resolver(existing, new) }
    }

    // There is no implicit ordering of the filter() results. Because we use HashMap, it's unlikely
    // user code which assumes a guaranteed ordering will get away with it consistently. (If we used
    // LinkedHashMap, there would be an implicit ordering based on *first sight of any particular
    // String* which would perhaps kind of work, until different Ts with the same String start
    // occurring and introducing subtle behaviour the caller might not expect.)
    fun filter(predicate: (T) -> Boolean) = storage.values.filter(predicate).toList()
}
