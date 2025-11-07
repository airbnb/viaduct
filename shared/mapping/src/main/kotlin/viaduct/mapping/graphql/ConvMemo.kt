package viaduct.mapping.graphql

/**
 * [ConvMemo] is a non-threadsafe recursion-aware memo for constructing [Conv]s
 *
 * ConvMemo helps with address 2 issues:
 * 1. Some types appear multiple times in a type tree but their [Conv] is invariant
 *   and only needs to be built once.
 *   [ConvMemo] memoizes Convs by their key, and can return a Conv that has
 *   already been built for a type.
 *
 * 1. Conv's are often arranged in eagerly-built trees, which are constructed ahead
 *   of time and reused. However, eagerly building a Conv tree for a recursive object
 *   can lead to infinite recursion and a StackOverflow exception.
 *   [ConvMemo] knows which Convs are being built in a stack frame and can return
 *   references to a Convs earlier in the stack that are still being constructed.
 *
 * Example:
 * ```
 *     val memo = ConvMemo<String, Int>()
 *
 *     // build a naive recursive implementation of str2Int
 *     fun mkStr2Int(): Conv<String, Int> {
 *         val inner = memo.buildIfAbsent("str2Int", ::mkStr2Int)
 *         return Conv(
 *             forward = {
 *                 if (it.isEmpty()) 0
 *                 else it.last().digitToInt() + (10 * (inner(it.dropLast(1))))
 *             },
 *             inverse = {
 *                 val digit = it.mod(10).toString()
 *                 val remainder = if (it > 10) inner.invert(it / 10) else ""
 *                 remainder + digit
 *             }
 *         )
 *     }
 *     val conv = memo.buildIfAbsent("str2Int", ::mkStr2Int)
 *         .also { memo.finalize() }
 *     assertEquals(123, conv("123"))
 *     assertEquals("123", conv.invert(123))
 *```
 */
class ConvMemo {
    private val building = mutableSetOf<String>()
    private val refs = mutableListOf<ConvRef>()
    private val convs = mutableMapOf<String, Conv<*, *>>()
    private var finalized = false

    /**
     * A Conv that can be lazily constructed, allowing for cyclic references.
     * @see [ConvMemo]
     */
    private class ConvRef(val memoKey: String) : Conv<Any?, Any?> {
        var inner: Conv<Any?, Any?>? = null

        override fun invoke(from: Any?): Any? = checkState().invoke(from)

        override fun invert(to: Any?): Any? = checkState().invert(to)

        override fun inverse(): Conv<Any?, Any?> = checkState().inverse()

        private fun checkState(): Conv<Any?, Any?> =
            checkNotNull(inner) {
                "Conv was invoked before calling `finalize` on CyclicConvBuilder"
            }
    }

    /**
     * Build a new Conv using the provided [fn], if this [ConvMemo] has not already seen
     * the provided [memoKey].
     *
     * If this [buildIfAbsent] was previously invoked with [memoKey], then a reference to the
     * earlier-built Conv will be returned and [fn] will not be invoked.
     *
     * In the event that [memoKey] label was seen in the same stack as the current call
     * (eg, [buildIfAbsent] is being used to construct a Conv for a recursive object), then
     * the returned [Conv] will be a reference to a Conv, and [finalize] must be called before
     * that [Conv] can be used.
     *
     * @see finalize
     */
    @Suppress("UNCHECKED_CAST")
    fun <From, To> buildIfAbsent(
        memoKey: String,
        fn: () -> Conv<From, To>
    ): Conv<From, To> {
        if (finalized) {
            throw IllegalStateException("CyclicConvBuilder cannot be used after being finalized")
        }

        if (memoKey in building) {
            // cycle detected!
            // return a ConvRef which will have its reference completed when finalize is called
            val ref = ConvRef(memoKey)
            refs += ref
            return ref as Conv<From, To>
        }

        if (memoKey in convs) {
            // we've already seen this memoKey, though not in the current stack. Return the previously
            // seen conv
            return convs[memoKey]!! as Conv<From, To>
        }

        // no cycle detected.
        // Add the current type to the set of convs being built
        building += memoKey
        val conv = fn()
        require(conv !is ConvRef) {
            "Unresolvable Conv cycle detected: root builder for Conv with memoKey `$memoKey` returned a ConvRef instead of a real conv"
        }
        building -= memoKey
        convs[memoKey] = conv
        return conv
    }

    /**
     * This method should be called exactly once at the end of building a potentially cyclic tree of Conv's
     * This will initialize all pending references to cyclic convs returned by [buildIfAbsent],
     * making them operational.
     */
    fun finalize() {
        check(!finalized) { "finalize should only be called once" }
        finalized = true

        refs.forEach { ref ->
            val conv = requireNotNull(convs[ref.memoKey]) {
                "Unsatisfied Conv ref: ${ref.memoKey}"
            }
            @Suppress("UNCHECKED_CAST")
            ref.inner = conv as Conv<Any?, Any?>
        }
    }
}
