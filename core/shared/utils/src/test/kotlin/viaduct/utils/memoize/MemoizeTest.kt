package viaduct.utils.memoize

import java.util.concurrent.ConcurrentHashMap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MemoizeTest {
    private class CounterFn : Function0<Unit> {
        var count = 0

        private fun incr() {
            count += 1
        }

        override fun invoke(): Unit = incr()

        fun asFn1(): Function1<Int, Unit> = { _ -> incr() }

        fun asFn2(): Function2<Int, Int, Unit> = { _, _ -> incr() }

        fun asFn3(): Function3<Int, Int, Int, Unit> = { _, _, _: Int -> incr() }

        fun asFn4(): Function4<Int, Int, Int, Int, Unit> = { _, _, _, _ -> incr() }

        fun asFn5(): Function5<Int, Int, Int, Int, Int, Unit> = { _, _, _, _, _ -> incr() }

        fun asFn6(): Function6<Int, Int, Int, Int, Int, Int, Unit> = { _, _, _, _, _, _ -> incr() }

        fun asFn7(): Function7<Int, Int, Int, Int, Int, Int, Int, Unit> = { _, _, _, _, _, _, _ -> incr() }
    }

    @Test
    fun testMemoizeFn1() {
        val c = CounterFn()
        val m = c.asFn1().memoize()
        m(0)
        m(0)
        assertEquals(c.count, 1)

        m(1)
        m(1)
        assertEquals(c.count, 2)
    }

    @Test
    fun testMemoizeFn1WithCache() {
        val c = CounterFn()
        val cache = ConcurrentHashMap<Int, Unit>()
        val m = c.asFn1().memoize(cache)

        m(0)
        m(0)
        assertTrue(cache.containsKey(0))
        assertEquals(cache.size, 1)

        m(1)
        m(1)
        assertTrue(cache.containsKey(1))
        assertEquals(cache.size, 2)
    }

    @Test
    fun testMemoizeFn1WithCacheAndKeyMapper() {
        val c = CounterFn()
        val cache = ConcurrentHashMap<Int, Unit>()
        // map all inputs to a single key
        val m = c.asFn1().memoize(cache) { _ -> 0 }

        m(0)
        m(1)
        m(2)
        assertTrue(cache.containsKey(0))
        assertEquals(cache.size, 1)
        assertEquals(c.count, 1)
    }

    @Test
    fun testMemoizeFn2() {
        val c = CounterFn()
        val m = c.asFn2().memoize()

        m(0, 1)
        m(1, 0)
        assertEquals(c.count, 2)

        m(0, 1)
        m(1, 0)
        assertEquals(c.count, 2)
    }

    @Test
    fun testMemoizeFn2WithCache() {
        val c = CounterFn()
        val cache = ConcurrentHashMap<Pair<Int, Int>, Unit>()
        val m = c.asFn2().memoize(cache)

        m(0, 1)
        m(1, 0)
        assertEquals(c.count, 2)
        assertEquals(cache.size, 2)
        assertTrue(cache.containsKey(Pair(0, 1)))
        assertTrue(cache.containsKey(Pair(1, 0)))

        m(0, 1)
        m(1, 0)
        assertEquals(c.count, 2)
        assertEquals(cache.size, 2)
    }

    @Test
    fun testMemoizeFn2WithCacheAndKeyMapper() {
        val c = CounterFn()
        val cache = ConcurrentHashMap<Pair<Int, Int>, Unit>()
        // map all inputs to a single key
        val m = c.asFn2().memoize(cache) { _, _ -> Pair(0, 0) }

        m(0, 1)
        m(1, 0)
        assert(c.count == 1)
        assert(cache.size == 1)
        assertTrue(cache.containsKey(Pair(0, 0)))
    }

    @Test
    fun testMemoizeFn3() {
        val c = CounterFn()
        val m = c.asFn3().memoize()

        m(0, 0, 1)
        m(0, 1, 0)
        m(1, 0, 0)
        assert(c.count == 3)

        m(0, 0, 1)
        m(0, 1, 0)
        m(1, 0, 0)
        assert(c.count == 3)
    }

    @Test
    fun testMemoizeFn3WithCache() {
        val c = CounterFn()
        val cache = ConcurrentHashMap<Triple<Int, Int, Int>, Unit>()
        val m = c.asFn3().memoize(cache)

        m(0, 0, 1)
        m(0, 1, 0)
        m(1, 0, 0)
        assert(c.count == 3)
        assert(cache.size == 3)
        assertTrue(cache.containsKey(Triple(0, 0, 1)))
        assertTrue(cache.containsKey(Triple(0, 1, 0)))
        assertTrue(cache.containsKey(Triple(1, 0, 0)))
    }

    @Test
    fun testMemoizeFn3WithCacheAndKeyMapper() {
        val c = CounterFn()
        val cache = ConcurrentHashMap<Triple<Int, Int, Int>, Unit>()
        // map all inputs to a single key
        val m = c.asFn3().memoize(cache) { _, _, _ -> Triple(0, 0, 0) }

        m(0, 0, 1)
        m(0, 1, 0)
        m(1, 0, 0)
        assert(c.count == 1)
        assert(cache.size == 1)
        assertTrue(cache.containsKey(Triple(0, 0, 0)))
    }

    @Test
    fun testMemoizeFn4() {
        val c = CounterFn()
        val m = c.asFn4().memoize()

        m(0, 0, 0, 1)
        m(0, 0, 1, 0)
        m(0, 1, 0, 0)
        m(1, 0, 0, 0)
        assert(c.count == 4)

        m(0, 0, 0, 1)
        m(0, 0, 1, 0)
        m(0, 1, 0, 0)
        m(1, 0, 0, 0)
        assert(c.count == 4)
    }

    @Test
    fun testMemoizeFn4WithCache() {
        val c = CounterFn()
        val cache = ConcurrentHashMap<Quadruple<Int, Int, Int, Int>, Unit>()
        val m = c.asFn4().memoize(cache)

        m(0, 0, 0, 1)
        m(0, 0, 1, 0)
        m(0, 1, 0, 0)
        m(1, 0, 0, 0)
        assert(c.count == 4)
        assert(cache.size == 4)
        assertTrue(cache.containsKey(Quadruple(0, 0, 0, 1)))
        assertTrue(cache.containsKey(Quadruple(0, 0, 1, 0)))
        assertTrue(cache.containsKey(Quadruple(0, 1, 0, 0)))
        assertTrue(cache.containsKey(Quadruple(1, 0, 0, 0)))
    }

    @Test
    fun testMemoizeFn4WithCacheAndKeyMapper() {
        val c = CounterFn()
        val cache = ConcurrentHashMap<Quadruple<Int, Int, Int, Int>, Unit>()
        // map all inputs to a single key
        val m = c.asFn4().memoize(cache) { _, _, _, _ -> Quadruple(0, 0, 0, 0) }

        m(0, 0, 0, 1)
        m(0, 0, 1, 0)
        m(0, 1, 0, 0)
        m(1, 0, 0, 0)
        assert(c.count == 1)
        assert(cache.size == 1)
        assertTrue(cache.containsKey(Quadruple(0, 0, 0, 0)))
    }

    @Test
    fun testMemoizeFn5() {
        val c = CounterFn()
        val m = c.asFn5().memoize()

        m(0, 0, 0, 0, 1)
        m(0, 0, 0, 1, 0)
        m(0, 0, 1, 0, 0)
        m(0, 1, 0, 0, 0)
        m(1, 0, 0, 0, 0)
        assert(c.count == 5)

        m(0, 0, 0, 0, 1)
        m(0, 0, 0, 1, 0)
        m(0, 0, 1, 0, 0)
        m(0, 1, 0, 0, 0)
        m(1, 0, 0, 0, 0)
        assert(c.count == 5)
    }

    @Test
    fun testMemoizeFn5WithCache() {
        val c = CounterFn()
        val cache = ConcurrentHashMap<Quintuple<Int, Int, Int, Int, Int>, Unit>()
        val m = c.asFn5().memoize(cache)

        m(0, 0, 0, 0, 1)
        m(0, 0, 0, 1, 0)
        m(0, 0, 1, 0, 0)
        m(0, 1, 0, 0, 0)
        m(1, 0, 0, 0, 0)
        assert(c.count == 5)
        assert(cache.size == 5)
        assertTrue(cache.containsKey(Quintuple(0, 0, 0, 0, 1)))
        (cache.containsKey(Quintuple(0, 0, 0, 1, 0)))
        (cache.containsKey(Quintuple(0, 0, 1, 0, 0)))
        (cache.containsKey(Quintuple(0, 1, 0, 0, 0)))
        (cache.containsKey(Quintuple(1, 0, 0, 0, 0)))
    }

    @Test
    fun testMemoizeFn6() {
        val c = CounterFn()
        val m = c.asFn6().memoize()

        m(0, 0, 0, 0, 0, 1)
        m(0, 0, 0, 0, 1, 0)
        m(0, 0, 0, 1, 0, 0)
        m(0, 0, 1, 0, 0, 0)
        m(0, 1, 0, 0, 0, 0)
        m(1, 0, 0, 0, 0, 0)
        assert(c.count == 6)

        m(0, 0, 0, 0, 0, 1)
        m(0, 0, 0, 0, 1, 0)
        m(0, 0, 0, 1, 0, 0)
        m(0, 0, 1, 0, 0, 0)
        m(0, 1, 0, 0, 0, 0)
        m(1, 0, 0, 0, 0, 0)
        assert(c.count == 6)
    }

    @Test
    fun testMemoizeFn6WithCache() {
        val c = CounterFn()
        val cache = ConcurrentHashMap<Sextuple<Int, Int, Int, Int, Int, Int>, Unit>()
        val m = c.asFn6().memoize(cache)

        m(0, 0, 0, 0, 0, 1)
        m(0, 0, 0, 0, 1, 0)
        m(0, 0, 0, 1, 0, 0)
        m(0, 0, 1, 0, 0, 0)
        m(0, 1, 0, 0, 0, 0)
        m(1, 0, 0, 0, 0, 0)
        assert(c.count == 6)
        assert(cache.size == 6)
        assertTrue(cache.containsKey(Sextuple(0, 0, 0, 0, 0, 1)))
        assert(cache.containsKey(Sextuple(0, 0, 0, 0, 1, 0)))
        assert(cache.containsKey(Sextuple(0, 0, 0, 1, 0, 0)))
        assert(cache.containsKey(Sextuple(0, 0, 1, 0, 0, 0)))
        assert(cache.containsKey(Sextuple(0, 1, 0, 0, 0, 0)))
        assert(cache.containsKey(Sextuple(1, 0, 0, 0, 0, 0)))
    }

    @Test
    fun testMemoizeFn7() {
        val c = CounterFn()
        val m = c.asFn7().memoize()

        m(0, 0, 0, 0, 0, 0, 1)
        m(0, 0, 0, 0, 0, 1, 0)
        m(0, 0, 0, 0, 1, 0, 0)
        m(0, 0, 0, 1, 0, 0, 0)
        m(0, 0, 1, 0, 0, 0, 0)
        m(0, 1, 0, 0, 0, 0, 0)
        m(1, 0, 0, 0, 0, 0, 0)
        assert(c.count == 7)

        m(0, 0, 0, 0, 0, 0, 1)
        m(0, 0, 0, 0, 0, 1, 0)
        m(0, 0, 0, 0, 1, 0, 0)
        m(0, 0, 0, 1, 0, 0, 0)
        m(0, 0, 1, 0, 0, 0, 0)
        m(0, 1, 0, 0, 0, 0, 0)
        m(1, 0, 0, 0, 0, 0, 0)
        assert(c.count == 7)
    }

    @Test
    fun testMemoizeFn7WithCache() {
        val c = CounterFn()
        val cache = ConcurrentHashMap<Septuple<Int, Int, Int, Int, Int, Int, Int>, Unit>()
        val m = c.asFn7().memoize(cache)

        m(0, 0, 0, 0, 0, 0, 1)
        m(0, 0, 0, 0, 0, 1, 0)
        m(0, 0, 0, 0, 1, 0, 0)
        m(0, 0, 0, 1, 0, 0, 0)
        m(0, 0, 1, 0, 0, 0, 0)
        m(0, 1, 0, 0, 0, 0, 0)
        m(1, 0, 0, 0, 0, 0, 0)
        assert(c.count == 7)
        assert(cache.size == 7)
        assert(cache.containsKey(Septuple(0, 0, 0, 0, 0, 0, 1)))
        assert(cache.containsKey(Septuple(0, 0, 0, 0, 0, 1, 0)))
        assert(cache.containsKey(Septuple(0, 0, 0, 0, 1, 0, 0)))
        assert(cache.containsKey(Septuple(0, 0, 0, 1, 0, 0, 0)))
        assert(cache.containsKey(Septuple(0, 0, 1, 0, 0, 0, 0)))
        assert(cache.containsKey(Septuple(0, 1, 0, 0, 0, 0, 0)))
        assert(cache.containsKey(Septuple(1, 0, 0, 0, 0, 0, 0)))
    }

    @Test
    fun `plain maps support default keys at every arity`() {
        val counter = CounterFn()
        val one = counter.asFn1().memoize(HashMap())
        val two = counter.asFn2().memoize(HashMap())
        val three = counter.asFn3().memoize(HashMap())
        val four = counter.asFn4().memoize(HashMap())
        val five = counter.asFn5().memoize(HashMap())
        val six = counter.asFn6().memoize(HashMap())
        val seven = counter.asFn7().memoize(HashMap())

        repeat(2) {
            one(0)
            two(0, 0)
            three(0, 0, 0)
            four(0, 0, 0, 0)
            five(0, 0, 0, 0, 0)
            six(0, 0, 0, 0, 0, 0)
            seven(0, 0, 0, 0, 0, 0, 0)
        }

        assertEquals(7, counter.count)
    }

    @Test
    fun `custom keys work with plain and concurrent caches at every arity`() {
        for (arity in 1..7) {
            for (cache in listOf(HashMap<String, Any>(), ConcurrentHashMap<String, Any>())) {
                var calls = 0
                val memoized = memoizeCustomKey(arity, cache) {
                    calls++
                    Any()
                }
                val zeroes = List(arity) { 0 }
                val cached = Any()
                cache["0"] = cached
                assertSame(cached, memoized(zeroes))
                assertEquals(0, calls)

                val first = memoized(zeroes.mapIndexed { index, value -> if (index == 0) 1 else value })
                val last = memoized(zeroes.mapIndexed { index, value -> if (index == arity - 1) 1 else value })
                assertSame(first, last)
                assertSame(first, cache["1"])
                assertEquals(1, calls)
            }
        }
    }

    @Test
    fun `concurrent caches preserve entries inserted during computation at every arity`() {
        for (arity in 1..7) {
            val cache = ConcurrentHashMap<String, Any>()
            val winner = Any()
            var calls = 0
            val memoized = memoizeCustomKey(arity, cache) {
                calls++
                cache["0"] = winner
                Any()
            }

            assertSame(winner, memoized(List(arity) { 0 }))
            assertSame(winner, cache["0"])
            assertSame(winner, memoized(List(arity) { 0 }))
            assertEquals(1, calls)
        }
    }

    private fun memoizeCustomKey(
        arity: Int,
        cache: MutableMap<String, Any>,
        compute: () -> Any,
    ): (List<Int>) -> Any =
        when (arity) {
            1 -> {
                val function: (Int) -> Any = { _ -> compute() }
                val keyMapper: (Int) -> String = { a -> a.toString() }
                val memoized = function.memoize(cache, keyMapper)
                val invoke: (List<Int>) -> Any = { args -> memoized(args[0]) }
                invoke
            }
            2 -> {
                val function: (Int, Int) -> Any = { _, _ -> compute() }
                val keyMapper: (Int, Int) -> String = { a, b -> (a + b).toString() }
                val memoized = function.memoize(cache, keyMapper)
                val invoke: (List<Int>) -> Any = { args -> memoized(args[0], args[1]) }
                invoke
            }
            3 -> {
                val function: (Int, Int, Int) -> Any = { _, _, _ -> compute() }
                val keyMapper: (Int, Int, Int) -> String = { a, b, c -> (a + b + c).toString() }
                val memoized = function.memoize(cache, keyMapper)
                val invoke: (List<Int>) -> Any = { args -> memoized(args[0], args[1], args[2]) }
                invoke
            }
            4 -> {
                val function: (Int, Int, Int, Int) -> Any = { _, _, _, _ -> compute() }
                val keyMapper: (Int, Int, Int, Int) -> String = { a, b, c, d -> (a + b + c + d).toString() }
                val memoized = function.memoize(cache, keyMapper)
                val invoke: (List<Int>) -> Any = { args -> memoized(args[0], args[1], args[2], args[3]) }
                invoke
            }
            5 -> {
                val function: (Int, Int, Int, Int, Int) -> Any = { _, _, _, _, _ -> compute() }
                val keyMapper: (Int, Int, Int, Int, Int) -> String = { a, b, c, d, e -> (a + b + c + d + e).toString() }
                val memoized = function.memoize(cache, keyMapper)
                val invoke: (List<Int>) -> Any = { args -> memoized(args[0], args[1], args[2], args[3], args[4]) }
                invoke
            }
            6 -> {
                val function: (Int, Int, Int, Int, Int, Int) -> Any = { _, _, _, _, _, _ -> compute() }
                val keyMapper: (Int, Int, Int, Int, Int, Int) -> String = { a, b, c, d, e, f -> (a + b + c + d + e + f).toString() }
                val memoized = function.memoize(cache, keyMapper)
                val invoke: (List<Int>) -> Any = { args -> memoized(args[0], args[1], args[2], args[3], args[4], args[5]) }
                invoke
            }
            7 -> {
                val function: (Int, Int, Int, Int, Int, Int, Int) -> Any = { _, _, _, _, _, _, _ -> compute() }
                val keyMapper: (Int, Int, Int, Int, Int, Int, Int) -> String = { a, b, c, d, e, f, g -> (a + b + c + d + e + f + g).toString() }
                val memoized = function.memoize(cache, keyMapper)
                val invoke: (List<Int>) -> Any = { args -> memoized(args[0], args[1], args[2], args[3], args[4], args[5], args[6]) }
                invoke
            }
            else -> error("Unsupported arity: $arity")
        }
}
