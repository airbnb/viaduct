package viaduct.utils.memoize

import java.util.concurrent.ConcurrentHashMap
import org.junit.jupiter.api.Assertions.assertEquals
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
}
