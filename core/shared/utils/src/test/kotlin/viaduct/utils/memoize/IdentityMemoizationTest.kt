package viaduct.utils.memoize

import java.util.IdentityHashMap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class IdentityMemoizationTest {
    private data class UnhashableKey(val value: String = "same") {
        override fun hashCode(): Int = error("Identity memoization must not call hashCode")
    }

    @Test
    fun `two argument identity memoization distinguishes equal instances and argument order`() {
        val a = UnhashableKey()
        val b = UnhashableKey()
        assertEquals(a, b)
        val compute: (UnhashableKey?, UnhashableKey?) -> Any = { _, _ -> Any() }
        val memoized = compute.memoize(HashMap(16), ::IdentityPair)

        val aa = memoized(a, a)
        val ab = memoized(a, b)
        val ba = memoized(b, a)
        assertNotSame(aa, ab)
        assertNotSame(aa, ba)
        assertNotSame(ab, ba)
        assertSame(aa, memoized(a, a))
        assertSame(ab, memoized(a, b))
        assertSame(ba, memoized(b, a))

        val nullFirst = memoized(null, a)
        val nullSecond = memoized(a, null)
        assertNotSame(nullFirst, nullSecond)
        assertSame(nullFirst, memoized(null, a))
        assertSame(nullSecond, memoized(a, null))
        assertSame(memoized(null, null), memoized(null, null))
    }

    @Test
    fun `three argument identity memoization includes every argument`() {
        val a = UnhashableKey()
        val b = UnhashableKey()
        val compute: (UnhashableKey?, UnhashableKey?, UnhashableKey?) -> Any = { _, _, _ -> Any() }
        val memoized = compute.memoize(HashMap(16), ::IdentityTriple)
        val original = memoized(a, a, a)

        for (other in listOf(b, null)) {
            val first = memoized(other, a, a)
            val second = memoized(a, other, a)
            val third = memoized(a, a, other)
            assertNotSame(original, first)
            assertNotSame(original, second)
            assertNotSame(original, third)
            assertNotSame(first, second)
            assertNotSame(second, third)
            assertSame(first, memoized(other, a, a))
            assertSame(second, memoized(a, other, a))
            assertSame(third, memoized(a, a, other))
        }
        assertSame(original, memoized(a, a, a))
        assertSame(memoized(null, null, null), memoized(null, null, null))
    }

    @Test
    fun `separate identity caches keep their results isolated`() {
        val key = UnhashableKey()
        val pair: (UnhashableKey, UnhashableKey) -> Any = { _, _ -> Any() }
        val triple: (UnhashableKey, UnhashableKey, UnhashableKey) -> Any = { _, _, _ -> Any() }
        val firstPair = pair.memoize(HashMap(16), ::IdentityPair)
        val secondPair = pair.memoize(HashMap(16), ::IdentityPair)
        val firstTriple = triple.memoize(HashMap(16), ::IdentityTriple)
        val secondTriple = triple.memoize(HashMap(16), ::IdentityTriple)

        assertNotSame(firstPair(key, key), secondPair(key, key))
        assertNotSame(firstTriple(key, key, key), secondTriple(key, key, key))
    }

    @Test
    fun `identity memoization retries failed computations`() {
        val key = UnhashableKey()
        var attempts = 0
        val compute: (UnhashableKey, UnhashableKey, UnhashableKey) -> Any = { _, _, _ ->
            check(++attempts > 1) { "First attempt fails" }
            Any()
        }
        val memoized = compute.memoize(HashMap(16), ::IdentityTriple)

        assertThrows<IllegalStateException> { memoized(key, key, key) }
        val result = memoized(key, key, key)

        assertSame(result, memoized(key, key, key))
        assertEquals(2, attempts)
    }

    @Test
    fun `recursive identity memoization visits shared children once per context`() {
        class Node(val children: List<Node>)
        val depth = 18
        val root = (0 until depth).fold(Node(emptyList())) { child, _ -> Node(listOf(child, child)) }
        var visits = 0
        lateinit var size: (Node, Any?) -> Int
        val compute: (Node, Any?) -> Int = { node, context ->
            visits++
            1 + node.children.sumOf { size(it, context) }
        }
        size = compute.memoize(HashMap(16), ::IdentityPair)
        val expectedSize = (1 shl (depth + 1)) - 1

        repeat(2) { assertEquals(expectedSize, size(root, null)) }
        assertEquals(depth + 1, visits)

        assertEquals(expectedSize, size(root, Any()))
        assertEquals(2 * (depth + 1), visits)
    }

    @Test
    fun `every arity compares all arguments by identity`() {
        for (arity in 1..7) {
            val first = UnhashableKey()
            val equalButDistinct = UnhashableKey()
            val original = List(arity) { first }
            val memoized = memoizeArity(arity) { Any() }
            val result = memoized(original)
            assertSame(result, memoized(original.toList()))
            for (position in 0 until arity) {
                for (replacement in listOf(equalButDistinct, null)) {
                    val changed = original.mapIndexed { index, value -> if (index == position) replacement else value }
                    val changedResult = memoized(changed)
                    assertNotSame(result, changedResult, "Argument $position of arity $arity")
                    assertSame(changedResult, memoized(changed))
                }
            }
            val nulls = List<UnhashableKey?>(arity) { null }
            assertSame(memoized(nulls), memoized(nulls))
        }
    }

    @Test
    fun `every arity retries exceptions and supports independent caches`() {
        for (arity in 1..7) {
            val arguments = List(arity) { UnhashableKey() }
            var attempts = 0
            val compute: () -> Any = {
                check(++attempts > 1)
                Any()
            }
            val memoized = memoizeArity(arity, initialCapacity = 0, compute)
            assertThrows<IllegalStateException> { memoized(arguments) }
            val result = memoized(arguments)
            assertSame(result, memoized(arguments))
            assertEquals(2, attempts)
            assertNotSame(result, memoizeArity(arity, compute = compute)(arguments))
            assertEquals(3, attempts)
        }
    }

    private fun memoizeArity(
        arity: Int,
        initialCapacity: Int = 16,
        compute: () -> Any,
    ): (List<UnhashableKey?>) -> Any =
        when (arity) {
            1 -> {
                val function: (UnhashableKey?) -> Any = { _ -> compute() }
                val memoized = function.memoize(IdentityHashMap(initialCapacity))
                val invoke: (List<UnhashableKey?>) -> Any = { args -> memoized(args[0]) }
                invoke
            }
            2 -> {
                val function: (UnhashableKey?, UnhashableKey?) -> Any = { _, _ -> compute() }
                val memoized = function.memoize(HashMap(initialCapacity), ::IdentityPair)
                val invoke: (List<UnhashableKey?>) -> Any = { args -> memoized(args[0], args[1]) }
                invoke
            }
            3 -> {
                val function: (UnhashableKey?, UnhashableKey?, UnhashableKey?) -> Any = { _, _, _ -> compute() }
                val memoized = function.memoize(HashMap(initialCapacity), ::IdentityTriple)
                val invoke: (List<UnhashableKey?>) -> Any = { args -> memoized(args[0], args[1], args[2]) }
                invoke
            }
            4 -> {
                val function: (UnhashableKey?, UnhashableKey?, UnhashableKey?, UnhashableKey?) -> Any = { _, _, _, _ -> compute() }
                val memoized = function.memoize(HashMap(initialCapacity), ::IdentityQuadruple)
                val invoke: (List<UnhashableKey?>) -> Any = { args -> memoized(args[0], args[1], args[2], args[3]) }
                invoke
            }
            5 -> {
                val function: (UnhashableKey?, UnhashableKey?, UnhashableKey?, UnhashableKey?, UnhashableKey?) -> Any = { _, _, _, _, _ -> compute() }
                val memoized = function.memoize(HashMap(initialCapacity), ::IdentityQuintuple)
                val invoke: (List<UnhashableKey?>) -> Any = { args -> memoized(args[0], args[1], args[2], args[3], args[4]) }
                invoke
            }
            6 -> {
                val function: (UnhashableKey?, UnhashableKey?, UnhashableKey?, UnhashableKey?, UnhashableKey?, UnhashableKey?) -> Any = { _, _, _, _, _, _ -> compute() }
                val memoized = function.memoize(HashMap(initialCapacity), ::IdentitySextuple)
                val invoke: (List<UnhashableKey?>) -> Any = { args -> memoized(args[0], args[1], args[2], args[3], args[4], args[5]) }
                invoke
            }
            7 -> {
                val function: (UnhashableKey?, UnhashableKey?, UnhashableKey?, UnhashableKey?, UnhashableKey?, UnhashableKey?, UnhashableKey?) -> Any = { _, _, _, _, _, _, _ -> compute() }
                val memoized = function.memoize(HashMap(initialCapacity), ::IdentitySeptuple)
                val invoke: (List<UnhashableKey?>) -> Any = { args -> memoized(args[0], args[1], args[2], args[3], args[4], args[5], args[6]) }
                invoke
            }
            else -> error("Unsupported arity: $arity")
        }
}
