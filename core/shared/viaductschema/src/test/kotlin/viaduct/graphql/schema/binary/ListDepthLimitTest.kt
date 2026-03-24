package viaduct.graphql.schema.binary

import org.junit.jupiter.api.Test

/**
 * Glass-box tests for BSchema's maximum list depth encoding limit.
 *
 * BSchema encodes list depth in a specific binary format that supports
 * up to depth 27. These tests verify the encoding works at this limit.
 */
class ListDepthLimitTest {
    @Test
    fun `List depth 27 - maximum supported depth`() {
        val sdl = """
            input Simple {
                maxDepth: [[[[[[[[[[[[[[[[[[[[[[[[[[[Int]]]]]]]]]]]]]]]]]]]]]]]]]]]
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `List depth 27 with nullable base but non-nullable innermost list`() {
        // Int is nullable (no ! after Int)
        // The innermost list (depth index 26) is non-nullable (! after first ])
        val sdl = """
            input Simple {
                maxDepthMixed: [[[[[[[[[[[[[[[[[[[[[[[[[[[Int]!]]]]]]]]]]]]]]]]]]]]]]]]]]
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }
}
