package viaduct.tenant.runtime.support

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.tenant.runtime.support.ConnectionArgumentsSupport.OffsetBounds

/**
 * Single source of truth for connection pagination offset/limit math. The Kotlin and Java
 * `ForwardConnectionArguments` / `BackwardConnectionArguments` /
 * `MultidirectionalConnectionArguments` interfaces delegate here, so the math is tested once.
 */
class ConnectionArgumentsSupportTest {
    private val support = ConnectionArgumentsSupport

    // ---- forward ----------------------------------------------------------------------------

    @Test
    fun `forward with first and no after starts at offset zero`() {
        assertEquals(OffsetBounds(0, 3), support.forwardOffsetLimit(first = 3, after = null, defaultPageSize = 20))
    }

    @Test
    fun `forward with an after cursor offsets to cursor plus one`() {
        val after = OffsetCursorCodec.encode(2)
        assertEquals(OffsetBounds(3, 3), support.forwardOffsetLimit(first = 3, after = after, defaultPageSize = 20))
    }

    @Test
    fun `forward without first defaults the limit to the default page size`() {
        assertEquals(OffsetBounds(0, 20), support.forwardOffsetLimit(first = null, after = null, defaultPageSize = 20))
    }

    @Test
    fun `forward validate throws when first is not positive`() {
        assertThrows<IllegalArgumentException> { support.validateForward(first = 0, after = null) }
    }

    @Test
    fun `forward validate throws for an invalid after cursor`() {
        assertThrows<IllegalArgumentException> { support.validateForward(first = 3, after = "not a valid cursor!!") }
    }

    @Test
    fun `forward rejects maximum after cursor because it cannot be advanced`() {
        val after = OffsetCursorCodec.encode(Int.MAX_VALUE)

        val exception = assertThrows<IllegalArgumentException> {
            support.forwardOffsetLimit(first = 3, after = after, defaultPageSize = 20)
        }

        assertEquals("after cursor cannot advance beyond Int.MAX_VALUE: $after", exception.message)
    }

    // ---- backward ---------------------------------------------------------------------------

    @Test
    fun `backward without before requires total count`() {
        assertTrue(support.backwardRequiresTotalCount(before = null))
        assertFalse(support.backwardRequiresTotalCount(before = OffsetCursorCodec.encode(7)))
    }

    @Test
    fun `backward without before returns the negative-offset tail signal`() {
        assertEquals(OffsetBounds(-3, 3), support.backwardOffsetLimit(last = 3, before = null, defaultPageSize = 20))
    }

    @Test
    fun `backward without before but known total count computes offset from the end`() {
        assertEquals(
            OffsetBounds(7, 3),
            support.backwardOffsetLimit(last = 3, before = null, totalCount = 10, defaultPageSize = 20),
        )
    }

    @Test
    fun `backward with a before cursor clamps offset and limit`() {
        val before = OffsetCursorCodec.encode(7)
        assertEquals(OffsetBounds(4, 3), support.backwardOffsetLimit(last = 3, before = before, defaultPageSize = 20))
    }

    @Test
    fun `backward validate throws when last is not positive`() {
        assertThrows<IllegalArgumentException> { support.validateBackward(last = 0, before = null) }
    }

    @Test
    fun `backward validate throws for an invalid before cursor`() {
        assertThrows<IllegalArgumentException> { support.validateBackward(last = 3, before = "not a valid cursor!!") }
    }

    // ---- multidirectional -------------------------------------------------------------------

    @Test
    fun `multidirectional with forward args uses forward math`() {
        assertEquals(
            OffsetBounds(0, 3),
            support.multidirectionalOffsetLimit(first = 3, after = null, last = null, before = null, defaultPageSize = 20),
        )
    }

    @Test
    fun `multidirectional with backward args only uses backward math`() {
        val before = OffsetCursorCodec.encode(7)
        assertEquals(
            OffsetBounds(4, 3),
            support.multidirectionalOffsetLimit(first = null, after = null, last = 3, before = before, defaultPageSize = 20),
        )
    }

    @Test
    fun `multidirectional with no args returns the first page at the default size`() {
        assertEquals(
            OffsetBounds(0, 20),
            support.multidirectionalOffsetLimit(first = null, after = null, last = null, before = null, defaultPageSize = 20),
        )
    }

    @Test
    fun `multidirectional mixing forward and backward throws from toOffsetLimit`() {
        assertThrows<IllegalArgumentException> {
            support.multidirectionalOffsetLimit(first = 3, after = null, last = 3, before = null, defaultPageSize = 20)
        }
    }

    @Test
    fun `multidirectional mixing forward and backward throws from validate`() {
        assertThrows<IllegalArgumentException> {
            support.validateMultidirectional(first = 3, after = null, last = 3, before = null)
        }
    }

    @Test
    fun `multidirectional requiresTotalCount is true only for backward without before`() {
        assertFalse(support.multidirectionalRequiresTotalCount(first = 3, after = null, last = null, before = null))
        assertTrue(support.multidirectionalRequiresTotalCount(first = null, after = null, last = 3, before = null))
        assertFalse(
            support.multidirectionalRequiresTotalCount(
                first = null,
                after = null,
                last = 3,
                before = OffsetCursorCodec.encode(7),
            ),
        )
    }
}
