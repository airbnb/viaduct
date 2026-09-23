package viaduct.codegen

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

/** Tests for [GeneratedAccessorNames], the collision check both GRT generators run before emitting. */
class GeneratedAccessorNamesTest {
    // Same order as the production list, so these tests observe the error text the generators produce.
    private val suffixes = listOf("OrThrow", "")

    @Test
    fun `strict suffix collision is rejected`() {
        val error = assertThrows<IllegalArgumentException> {
            GeneratedAccessorNames.validateNoCollisions(
                "Example",
                mapOf("foo" to "getFoo", "fooOrThrow" to "getFooOrThrow"),
                suffixes,
            )
        }
        assertTrue(error.message!!.contains("type `Example`"), error.message)
        assertTrue(error.message!!.contains("fields `foo` and `fooOrThrow` both generate `getFooOrThrow`"), error.message)
    }

    /** `barOrNull` is an ordinary field name: no suffix makes it collide with `bar`'s accessor. */
    @Test
    fun `a field whose name ends in OrNull is accepted`() {
        assertDoesNotThrow {
            GeneratedAccessorNames.validateNoCollisions(
                "Example",
                mapOf("bar" to "getBar", "barOrNull" to "getBarOrNull"),
                suffixes,
            )
        }
    }

    /**
     * The Kotlin generators leave an `isX` field's accessor name alone, so the collision shows up
     * without a `get` prefix on either side.
     */
    @Test
    fun `is-prefixed collision is rejected`() {
        val error = assertThrows<IllegalArgumentException> {
            GeneratedAccessorNames.validateNoCollisions(
                "Example",
                mapOf("isReady" to "isReady", "isReadyOrThrow" to "isReadyOrThrow"),
                suffixes,
            )
        }
        assertTrue(error.message!!.contains("both generate `isReadyOrThrow`"), error.message)
    }

    /** Two field names differing only in leading case map to one accessor. */
    @Test
    fun `case-only collision is rejected once`() {
        val error = assertThrows<IllegalArgumentException> {
            GeneratedAccessorNames.validateNoCollisions(
                "Example",
                mapOf("foo" to "getFoo", "Foo" to "getFoo"),
                suffixes,
            )
        }
        assertTrue(error.message!!.contains("fields `foo` and `Foo` both generate"), error.message)
        assertEquals(1, Regex("both generate").findAll(error.message!!).count(), error.message)
    }

    /**
     * A duplicate suffix would make a single field generate the same accessor twice, which the
     * collision loop cannot distinguish from two fields colliding. Reject it at the boundary.
     */
    @Test
    fun `duplicate suffixes are rejected`() {
        val error = assertThrows<IllegalArgumentException> {
            GeneratedAccessorNames.validateNoCollisions(
                "Example",
                mapOf("foo" to "getFoo"),
                listOf("OrThrow", "", "OrThrow"),
            )
        }
        assertTrue(error.message!!.contains("Duplicate accessor suffixes"), error.message)
    }

    @Test
    fun `all collisions are reported`() {
        val error = assertThrows<IllegalArgumentException> {
            GeneratedAccessorNames.validateNoCollisions(
                "Example",
                mapOf(
                    "foo" to "getFoo",
                    "fooOrThrow" to "getFooOrThrow",
                    "isReady" to "isReady",
                    "isReadyOrThrow" to "isReadyOrThrow",
                ),
                suffixes,
            )
        }
        assertTrue(error.message!!.contains("both generate `getFooOrThrow`"), error.message)
        assertTrue(error.message!!.contains("both generate `isReadyOrThrow`"), error.message)
    }

    /** `fooOrThrow` alone generates `getFooOrThrow` and `getFooOrThrowOrThrow`. */
    @Test
    fun `suffix-named field without a sibling is accepted`() {
        assertDoesNotThrow {
            GeneratedAccessorNames.validateNoCollisions(
                "Example",
                mapOf("fooOrThrow" to "getFooOrThrow"),
                suffixes,
            )
        }
    }

    @Test
    fun `ordinary fields are accepted`() {
        assertDoesNotThrow {
            GeneratedAccessorNames.validateNoCollisions(
                "Example",
                mapOf("id" to "getId", "name" to "getName", "isReady" to "isReady"),
                suffixes,
            )
        }
    }
}
