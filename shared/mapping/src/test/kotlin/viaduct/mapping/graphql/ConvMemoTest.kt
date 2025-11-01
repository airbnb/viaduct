package viaduct.mapping.graphql

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.fail

class ConvMemoTest {
    @Test
    fun `documentation example`() {
        val memo = ConvMemo()

        // build a recursive implementation of str2Int
        fun mkStr2Int(): Conv<String, Int> {
            val inner = memo.buildIfAbsent("str2Int", ::mkStr2Int)
            return Conv(
                {
                    if (it.isEmpty()) {
                        0
                    } else {
                        it.last().digitToInt() + (10 * (inner(it.dropLast(1))))
                    }
                },
                {
                    val digit = it.mod(10).toString()
                    val remainder = if (it > 10) inner.invert(it / 10) else ""
                    remainder + digit
                }
            )
        }
        val conv = memo.buildIfAbsent("str2Int", ::mkStr2Int)
            .also { memo.finalize() }

        assertEquals(123, conv("123"))
        assertEquals("123", conv.invert(123))
    }

    @Test
    fun `buildIfAbsent -- builds non-cyclic convs without finalization`() {
        val builder = ConvMemo()
        val conv = builder.buildIfAbsent("Conv") { Conv.identity<Any>() }
        assertRoundtrip(conv, 1, 1)
    }

    @Test
    fun `buildIfAbsent -- throws when building unresolvable cycles`() {
        val cb = ConvMemo()

        val err = assertThrows<IllegalArgumentException> {
            // this starts building a real conv
            cb.buildIfAbsent("test-key") {
                // this returns a reference to the building conv and `fn` is never invoked
                cb.buildIfAbsent("test-key") {
                    // this never gets invoked
                    Conv.identity<Any>()
                }
            }
        }
        assertTrue(err.message?.contains("cycle") == true)
        assertTrue(err.message?.contains("test-key") == true)
    }

    @Test
    fun `buildIfAbsent -- builds cyclic convs`() {
        val cb = ConvMemo()

        // Build a Conv for this kind of structure:
        //  type Obj { x:Obj, y:Int }
        fun mkObj(): Conv<Any?, IR.Value> =
            cb.buildIfAbsent("Obj") {
                val fields = mapOf(
                    "x" to mkObj(),
                    "y" to Conv(
                        { if (it is Int) IR.Value.Number(it) else IR.Value.Null },
                        { (it as? IR.Value.Number)?.int }
                    )
                )
                Conv(
                    forward = {
                        if (it == null) {
                            IR.Value.Null
                        } else {
                            it as Map<String, Any?>
                            IR.Value.Object(
                                "Obj",
                                fields.mapValues { (k, conv) -> conv(it[k]) }
                            )
                        }
                    },
                    inverse = {
                        if (it == IR.Value.Null) {
                            null
                        } else {
                            it as IR.Value.Object
                            it.fields.mapValues { (k, v) -> fields[k]!!.invert(v) }
                        }
                    }
                )
            }

        val conv = mkObj()
        cb.finalize()

        val inp = mapOf("x" to mapOf("x" to null, "y" to 2), "y" to null)
        val inp2 = conv.invert(conv(inp))
        assertEquals(inp, inp2)
    }

    @Test
    fun `buildIfAbsent -- memoizes by memoKey`() {
        // build 2 Convs with the same memoKey. ConvBuilder should not invoke the
        // builder of the second call
        val cb = ConvMemo()
        val c1 = cb.buildIfAbsent<Any, Any>("") { Conv({}, {}) }
        val c2 = cb.buildIfAbsent<Any, Any>("") { fail("Should not be called") }

        assertSame(c1, c2)
    }

    @Test
    fun `buildIfAbsent -- cyclic convs throw if finalize is not called`() {
        val builder = ConvMemo()

        fun conv(inner: Conv<Any, Any>): Conv<Any, Any> = Conv(forward = inner::invoke, inverse = inner::invert)

        val conv = builder.buildIfAbsent("Conv") {
            conv(
                builder.buildIfAbsent("Conv") { Conv.identity() }
            )
        }

        assertThrows<IllegalStateException> { conv(1) }
            .let {
                assertTrue(it.message?.contains("finalize") == true)
            }
        assertThrows<IllegalStateException> { conv.invert(1) }
            .let {
                assertTrue(it.message?.contains("finalize") == true)
            }
    }

    @Test
    fun `buildIfAbsent -- throws when called after finalize`() {
        val builder = ConvMemo()
        builder.finalize()

        assertThrows<IllegalStateException> {
            builder.buildIfAbsent("Conv") { Conv.identity<Any>() }
        }.let {
            assertTrue(it.message?.contains("after being finalized") == true)
        }
    }

    @Test
    fun `finalize -- throws if called multiple times`() {
        val builder = ConvMemo()
        builder.finalize()
        assertThrows<IllegalStateException> {
            builder.finalize()
        }.let {
            assertTrue(it.message?.contains("once") == true)
        }
    }
}
