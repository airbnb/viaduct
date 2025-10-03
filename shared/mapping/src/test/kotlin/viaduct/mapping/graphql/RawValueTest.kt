package viaduct.mapping.graphql

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class RawValueTest : RawValue.DSL() {
    @Test
    fun dsl() {
        assertEquals(enull, RawENull)
        assertEquals(inull, RawINull)

        val any = Any()
        assertEquals(RawScalar("Any", any), any.scalar("Any"))
        assertEquals(RawScalar("Boolean", true), true.scalar)
        assertEquals(RawScalar("Float", 1.0), 1.0.scalar)
        assertEquals(RawScalar("Int", 1), 1.scalar)
        assertEquals(RawScalar("String", ""), "".scalar)

        assertEquals(RawList(listOf(RawScalar("Int", 1))), list(1.scalar))

        assertEquals(RawEnum("Name"), enum("Name"))
        assertEquals(RawInput(listOf("a" to RawENull)), input("a" to enull))
        assertEquals(RawList(listOf(RawScalar("Int", 1), RawENull)), list(1.scalar, enull))
        assertEquals(
            RawObject("Type", listOf("a" to RawScalar("Int", 1), "b" to RawENull)),
            obj("Type", "a" to 1.scalar, "b" to enull)
        )
    }

    @Test
    fun nullish() {
        assertTrue(enull.nullish)
        assertTrue(inull.nullish)

        listOf(
            1.scalar,
            "".scalar,
            list(),
            list(1.scalar),
            obj("Foo"),
            obj("Foo", "k" to 1.scalar),
            obj("Foo", "k" to enull),
            input(),
            input("k" to 1.scalar),
            enum("A")
        ).forEach {
            assertFalse(it.nullish, it.toString())
        }
    }

    @Test
    fun `str`() {
        assertEquals(
            "[(x, 1), (y, 2)]",
            input("x" to 1.scalar, "y" to 2.scalar).toString()
        )

        assertEquals("X", "X".enum.toString())

        assertEquals("INULL", inull.toString())
    }

    @Test
    fun ifNotINull() {
        fun test(
            v: RawValue,
            expIsInull: Boolean
        ) {
            val actIsInull = v.ifNotINull { false } ?: true
            assertEquals(expIsInull, actIsInull) { v.toString() }
        }

        test(inull, true)
        test(enull, false)
        test("null".scalar, false)
        test(list(), false)
        test(list(inull), false)
        test(input("k" to inull), false)
        test(enum("a"), false)
        test(obj("Type"), false)
    }

    @Test
    fun `RawScalar validation`() {
        assertThrows<IllegalArgumentException> {
            RawScalar("Test", enull)
        }
    }

    @Test
    fun `RawObject empty`() {
        assertTrue(RawObject.empty("Test").values.isEmpty())
    }

    @Test
    fun `RawObject plus`() {
        assertEquals(
            obj("Test", "x" to RawENull),
            RawObject.empty("Test") + ("x" to RawENull)
        )
    }
}
