@file:OptIn(ExperimentalApi::class)

package viaduct.api.connection

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.api.types.BackwardConnectionArguments
import viaduct.api.types.ForwardConnectionArguments
import viaduct.api.types.MultidirectionalConnectionArguments
import viaduct.apiannotations.ExperimentalApi

class ConnectionArgumentsTest {
    // Test implementations for the interfaces
    private data class TestForwardArgs(
        override val first: Int? = null,
        override val after: String? = null
    ) : ForwardConnectionArguments

    private data class TestBackwardArgs(
        override val last: Int? = null,
        override val before: String? = null
    ) : BackwardConnectionArguments

    private data class TestMultidirectionalArgs(
        override val first: Int? = null,
        override val after: String? = null,
        override val last: Int? = null,
        override val before: String? = null
    ) : MultidirectionalConnectionArguments

    // =============================================================================
    // ForwardConnectionArguments tests
    // =============================================================================

    @Test
    fun `forward toOffsetLimit with no args uses defaults`() {
        val args = TestForwardArgs()
        val result = args.toOffsetLimit()
        assertEquals(0, result.offset)
        assertEquals(20, result.limit)
    }

    @Test
    fun `forward toOffsetLimit with first uses first as limit`() {
        val args = TestForwardArgs(first = 10)
        val result = args.toOffsetLimit()
        assertEquals(0, result.offset)
        assertEquals(10, result.limit)
    }

    @Test
    fun `forward toOffsetLimit with after cursor calculates offset`() {
        val cursor = OffsetCursor.fromOffset(5)
        val args = TestForwardArgs(after = cursor.value)
        val result = args.toOffsetLimit()
        assertEquals(6, result.offset) // after offset 5 means start at 6
        assertEquals(20, result.limit)
    }

    @Test
    fun `forward toOffsetLimit with first and after`() {
        val cursor = OffsetCursor.fromOffset(10)
        val args = TestForwardArgs(first = 5, after = cursor.value)
        val result = args.toOffsetLimit()
        assertEquals(11, result.offset) // after offset 10 means start at 11
        assertEquals(5, result.limit)
    }

    @Test
    fun `forward toOffsetLimit respects custom defaultPageSize`() {
        val args = TestForwardArgs()
        val result = args.toOffsetLimit(defaultPageSize = 100)
        assertEquals(0, result.offset)
        assertEquals(100, result.limit)
    }

    @Test
    fun `forward validate passes with valid args`() {
        val cursor = OffsetCursor.fromOffset(5)
        val args = TestForwardArgs(first = 10, after = cursor.value)
        args.validate() // Should not throw
    }

    @Test
    fun `forward validate throws on zero first`() {
        val args = TestForwardArgs(first = 0)
        val exception = assertThrows<IllegalArgumentException> {
            args.validate()
        }
        assertEquals("first must be positive, got: 0", exception.message)
    }

    @Test
    fun `forward validate throws on negative first`() {
        val args = TestForwardArgs(first = -5)
        val exception = assertThrows<IllegalArgumentException> {
            args.validate()
        }
        assertEquals("first must be positive, got: -5", exception.message)
    }

    @Test
    fun `forward validate throws on invalid after cursor`() {
        val args = TestForwardArgs(after = "invalid-cursor")
        val exception = assertThrows<IllegalArgumentException> {
            args.validate()
        }
        assertEquals("Invalid after cursor: invalid-cursor", exception.message)
    }

    @Test
    fun `forward toOffsetLimit throws on invalid args`() {
        val args = TestForwardArgs(first = -1)
        assertThrows<IllegalArgumentException> {
            args.toOffsetLimit()
        }
    }

    // =============================================================================
    // BackwardConnectionArguments tests
    // =============================================================================

    @Test
    fun `backward toOffsetLimit with no args uses defaults and sets fromEnd`() {
        val args = TestBackwardArgs()
        val result = args.toOffsetLimit()
        // No before cursor: offset resolved against list size at pagination time
        assertEquals(0, result.offset)
        assertEquals(20, result.limit)
        assertTrue(result.backwards)
    }

    @Test
    fun `backward toOffsetLimit with last uses last as limit and sets fromEnd`() {
        val args = TestBackwardArgs(last = 10)
        val result = args.toOffsetLimit()
        assertEquals(0, result.offset)
        assertEquals(10, result.limit)
        assertTrue(result.backwards)
    }

    @Test
    fun `backward toOffsetLimit with before cursor calculates offset`() {
        val cursor = OffsetCursor.fromOffset(50)
        val args = TestBackwardArgs(before = cursor.value)
        val result = args.toOffsetLimit()
        assertEquals(30, result.offset) // 50 - 20 = 30
        assertEquals(20, result.limit)
    }

    @Test
    fun `backward toOffsetLimit with last and before`() {
        val cursor = OffsetCursor.fromOffset(20)
        val args = TestBackwardArgs(last = 5, before = cursor.value)
        val result = args.toOffsetLimit()
        assertEquals(15, result.offset) // 20 - 5 = 15
        assertEquals(5, result.limit)
    }

    @Test
    fun `backward toOffsetLimit clamps offset to zero and adjusts limit`() {
        val cursor = OffsetCursor.fromOffset(3)
        val args = TestBackwardArgs(last = 10, before = cursor.value)
        val result = args.toOffsetLimit()
        assertEquals(0, result.offset) // max(0, 3 - 10) = 0
        assertEquals(3, result.limit) // min(10, 3) = 3, only 3 items before cursor
    }

    @Test
    fun `backward toOffsetLimit respects custom defaultPageSize`() {
        val cursor = OffsetCursor.fromOffset(100)
        val args = TestBackwardArgs(before = cursor.value)
        val result = args.toOffsetLimit(defaultPageSize = 50)
        assertEquals(50, result.offset) // 100 - 50 = 50
        assertEquals(50, result.limit)
    }

    @Test
    fun `backward validate passes with valid args`() {
        val cursor = OffsetCursor.fromOffset(50)
        val args = TestBackwardArgs(last = 10, before = cursor.value)
        args.validate() // Should not throw
    }

    @Test
    fun `backward validate throws on zero last`() {
        val args = TestBackwardArgs(last = 0)
        val exception = assertThrows<IllegalArgumentException> {
            args.validate()
        }
        assertEquals("last must be positive, got: 0", exception.message)
    }

    @Test
    fun `backward validate throws on negative last`() {
        val args = TestBackwardArgs(last = -3)
        val exception = assertThrows<IllegalArgumentException> {
            args.validate()
        }
        assertEquals("last must be positive, got: -3", exception.message)
    }

    @Test
    fun `backward validate throws on invalid before cursor`() {
        val args = TestBackwardArgs(before = "bad-cursor")
        val exception = assertThrows<IllegalArgumentException> {
            args.validate()
        }
        assertEquals("Invalid before cursor: bad-cursor", exception.message)
    }

    @Test
    fun `backward toOffsetLimit throws on invalid args`() {
        val args = TestBackwardArgs(last = -1)
        assertThrows<IllegalArgumentException> {
            args.toOffsetLimit()
        }
    }

    // =============================================================================
    // MultidirectionalConnectionArguments tests
    // =============================================================================

    @Test
    fun `multidirectional toOffsetLimit with no args uses defaults`() {
        val args = TestMultidirectionalArgs()
        val result = args.toOffsetLimit()
        assertEquals(0, result.offset)
        assertEquals(20, result.limit)
    }

    @Test
    fun `multidirectional toOffsetLimit with forward args uses forward pagination`() {
        val cursor = OffsetCursor.fromOffset(10)
        val args = TestMultidirectionalArgs(first = 5, after = cursor.value)
        val result = args.toOffsetLimit()
        assertEquals(11, result.offset)
        assertEquals(5, result.limit)
    }

    @Test
    fun `multidirectional toOffsetLimit with only first uses forward pagination`() {
        val args = TestMultidirectionalArgs(first = 15)
        val result = args.toOffsetLimit()
        assertEquals(0, result.offset)
        assertEquals(15, result.limit)
    }

    @Test
    fun `multidirectional toOffsetLimit with only after uses forward pagination`() {
        val cursor = OffsetCursor.fromOffset(5)
        val args = TestMultidirectionalArgs(after = cursor.value)
        val result = args.toOffsetLimit()
        assertEquals(6, result.offset)
        assertEquals(20, result.limit)
    }

    @Test
    fun `multidirectional toOffsetLimit with backward args uses backward pagination`() {
        val cursor = OffsetCursor.fromOffset(30)
        val args = TestMultidirectionalArgs(last = 10, before = cursor.value)
        val result = args.toOffsetLimit()
        assertEquals(20, result.offset)
        assertEquals(10, result.limit)
    }

    @Test
    fun `multidirectional toOffsetLimit with only last uses backward pagination`() {
        val args = TestMultidirectionalArgs(last = 8)
        val result = args.toOffsetLimit()
        assertEquals(0, result.offset)
        assertEquals(8, result.limit)
        assertTrue(result.backwards)
    }

    @Test
    fun `multidirectional toOffsetLimit with only before uses backward pagination`() {
        val cursor = OffsetCursor.fromOffset(40)
        val args = TestMultidirectionalArgs(before = cursor.value)
        val result = args.toOffsetLimit()
        assertEquals(20, result.offset)
        assertEquals(20, result.limit)
    }

    @Test
    fun `multidirectional toOffsetLimit respects custom defaultPageSize`() {
        val args = TestMultidirectionalArgs()
        val result = args.toOffsetLimit(defaultPageSize = 30)
        assertEquals(0, result.offset)
        assertEquals(30, result.limit)
    }

    @Test
    fun `multidirectional validate passes with forward args only`() {
        val cursor = OffsetCursor.fromOffset(5)
        val args = TestMultidirectionalArgs(first = 10, after = cursor.value)
        args.validate() // Should not throw
    }

    @Test
    fun `multidirectional validate passes with backward args only`() {
        val cursor = OffsetCursor.fromOffset(50)
        val args = TestMultidirectionalArgs(last = 10, before = cursor.value)
        args.validate() // Should not throw
    }

    @Test
    fun `multidirectional validate passes with no args`() {
        val args = TestMultidirectionalArgs()
        args.validate() // Should not throw
    }

    @Test
    fun `multidirectional validate throws when mixing first and last`() {
        val args = TestMultidirectionalArgs(first = 10, last = 5)
        val exception = assertThrows<IllegalArgumentException> {
            args.validate()
        }
        assertEquals("Cannot mix forward (first/after) and backward (last/before) pagination", exception.message)
    }

    @Test
    fun `multidirectional validate throws when mixing first and before`() {
        val cursor = OffsetCursor.fromOffset(20)
        val args = TestMultidirectionalArgs(first = 10, before = cursor.value)
        val exception = assertThrows<IllegalArgumentException> {
            args.validate()
        }
        assertEquals("Cannot mix forward (first/after) and backward (last/before) pagination", exception.message)
    }

    @Test
    fun `multidirectional validate throws when mixing after and last`() {
        val afterCursor = OffsetCursor.fromOffset(5)
        val args = TestMultidirectionalArgs(after = afterCursor.value, last = 10)
        val exception = assertThrows<IllegalArgumentException> {
            args.validate()
        }
        assertEquals("Cannot mix forward (first/after) and backward (last/before) pagination", exception.message)
    }

    @Test
    fun `multidirectional validate throws when mixing after and before`() {
        val afterCursor = OffsetCursor.fromOffset(5)
        val beforeCursor = OffsetCursor.fromOffset(20)
        val args = TestMultidirectionalArgs(after = afterCursor.value, before = beforeCursor.value)
        val exception = assertThrows<IllegalArgumentException> {
            args.validate()
        }
        assertEquals("Cannot mix forward (first/after) and backward (last/before) pagination", exception.message)
    }

    @Test
    fun `multidirectional validate throws when mixing all args`() {
        val afterCursor = OffsetCursor.fromOffset(5)
        val beforeCursor = OffsetCursor.fromOffset(20)
        val args = TestMultidirectionalArgs(
            first = 10,
            after = afterCursor.value,
            last = 5,
            before = beforeCursor.value
        )
        val exception = assertThrows<IllegalArgumentException> {
            args.validate()
        }
        assertEquals("Cannot mix forward (first/after) and backward (last/before) pagination", exception.message)
    }

    @Test
    fun `multidirectional validate throws on invalid first`() {
        val args = TestMultidirectionalArgs(first = -1)
        val exception = assertThrows<IllegalArgumentException> {
            args.validate()
        }
        assertEquals("first must be positive, got: -1", exception.message)
    }

    @Test
    fun `multidirectional validate throws on invalid last`() {
        val args = TestMultidirectionalArgs(last = 0)
        val exception = assertThrows<IllegalArgumentException> {
            args.validate()
        }
        assertEquals("last must be positive, got: 0", exception.message)
    }

    @Test
    fun `multidirectional validate throws on invalid after cursor`() {
        val args = TestMultidirectionalArgs(after = "invalid")
        val exception = assertThrows<IllegalArgumentException> {
            args.validate()
        }
        assertEquals("Invalid after cursor: invalid", exception.message)
    }

    @Test
    fun `multidirectional validate throws on invalid before cursor`() {
        val args = TestMultidirectionalArgs(before = "invalid")
        val exception = assertThrows<IllegalArgumentException> {
            args.validate()
        }
        assertEquals("Invalid before cursor: invalid", exception.message)
    }

    @Test
    fun `multidirectional toOffsetLimit throws on mixed args`() {
        val args = TestMultidirectionalArgs(first = 10, last = 5)
        assertThrows<IllegalArgumentException> {
            args.toOffsetLimit()
        }
    }
}
