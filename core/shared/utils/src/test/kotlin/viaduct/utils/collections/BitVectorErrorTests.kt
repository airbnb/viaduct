package viaduct.utils.collections

import java.util.function.BiConsumer
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

internal class BitVectorErrorTests : BitVectorSetup() {
    /**
     * What we're doing here could be a parameterized type, but they generate a lot of "nominal" test
     * cases which is more noise than signal in the test output. This approach has the down-side that
     * testing stops after the first test-case fails, but is less noisy in the general case.
     */
    private fun boundsErrors(test: BiConsumer<Int?, Int?>) {
        for (i in BOUNDS_PARAMETERS.indices) {
            val sz = BOUNDS_PARAMETERS[i][0]
            val idx = BOUNDS_PARAMETERS[i][1]
            assertThrows(
                IndexOutOfBoundsException::class.java,
                {
                    test.accept(sz, idx)
                },
                { "size=$sz, index=$idx" }
            )
        }
    }

    @Test
    fun getBoundsErrors() {
        val test = BiConsumer { sz: Int?, idx: Int? -> mk(sz!!).get(idx!!) }
        boundsErrors(test)
    }

    @Test
    fun setBoundsErrors() {
        val test = BiConsumer { sz: Int?, idx: Int? -> mk(sz!!).set(idx!!) }
        boundsErrors(test)
    }

    @Test
    fun clearBoundsErrors() {
        val test = BiConsumer { sz: Int?, idx: Int? -> mk(sz!!).clear(idx!!) }
        boundsErrors(test)
    }

    @Test
    fun getRangeBoundsErrors() {
        assertThrows(
            IndexOutOfBoundsException::class.java
        ) {
            mk(0).get(-1, 0)
        }
        assertThrows(
            IndexOutOfBoundsException::class.java
        ) {
            mk(1).get(-5, 0)
        }
        assertThrows(
            IndexOutOfBoundsException::class.java
        ) {
            mk(1).get(0, 2)
        }

        assertThrows(
            IndexOutOfBoundsException::class.java
        ) {
            mk(WS).get(-1, 2)
        }
        assertThrows(
            IndexOutOfBoundsException::class.java
        ) {
            mk(WS).get(
                WS - 4,
                5
            )
        }
        assertThrows(
            IndexOutOfBoundsException::class.java
        ) {
            mk(WS).get(
                0,
                WS + 1
            )
        }

        assertThrows(
            IndexOutOfBoundsException::class.java
        ) {
            mk(2 * WS).get(
                2 * WS - 1,
                WS
            )
        }
    }

    @Test
    fun getRangeCountErrors() {
        assertThrows(
            IllegalArgumentException::class.java
        ) {
            mk(0).get(0, -1)
        }
        assertThrows(
            IllegalArgumentException::class.java
        ) {
            mk(1).get(0, -1)
        }
        assertThrows(
            IllegalArgumentException::class.java
        ) {
            mk(1).get(0, -2)
        }
        assertThrows(
            IllegalArgumentException::class.java
        ) {
            mk(1).get(0, -2)
        }
        assertThrows(
            IllegalArgumentException::class.java
        ) {
            mk(WS).get(0, -10001000)
        }
        assertThrows(
            IllegalArgumentException::class.java
        ) {
            mk(2 * WS).get(
                0,
                WS + 1
            )
        }
        assertThrows(
            IllegalArgumentException::class.java
        ) {
            mk(WS * 10001000 + 1).get(
                0,
                WS * 10001000
            )
        }
    }

    @Test
    fun builderErrors() {
        assertThrows(
            IllegalArgumentException::class.java
        ) {
            mkb().add(0L, -1)
        }
        assertThrows(
            IllegalArgumentException::class.java
        ) {
            mkb().add(0L, WS + 1)
        }
        assertThrows(
            IllegalStateException::class.java
        ) {
            val b = mkb()
            b.build()
            b.add(0L, 1)
        }
        assertThrows(
            IllegalStateException::class.java
        ) {
            val b = mkb()
            b.build()
            b.build()
        }
    }

    companion object {
        val BOUNDS_PARAMETERS: Array<IntArray> = arrayOf(
            intArrayOf(0, 0),
            intArrayOf(0, -1),
            intArrayOf(1, -5),
            intArrayOf(1, 1),
            intArrayOf(1, 2),
            intArrayOf(WS, -1),
            intArrayOf(WS, WS + 3),
            intArrayOf(WS, 2 * WS),
            intArrayOf(2 * WS, -1),
            intArrayOf(2 * WS, 2 * WS + 1),
            intArrayOf(2 * WS, (WS * 1010001000L).toInt())
        )
    }
}
