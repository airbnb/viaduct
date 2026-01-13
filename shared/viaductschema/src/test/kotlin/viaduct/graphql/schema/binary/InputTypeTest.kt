package viaduct.graphql.schema.binary

import org.junit.jupiter.api.Test

/**
 * Test encoding and decoding of simple and input-object types,
 * including many type-expr cases.
 *
 * (These are in a separate file (a) to keep file lengths down but
 * also (b) because when object types appear in the schema,
 * graphql-java inserts directives into the schema, and we didn't
 * initially have support for directives.)
 */
class InputTypeTest {
    @Test
    fun `Simple scalar type def`() {
        val sdl = "scalar Int"
        assertRoundTrip(sdl, "")
    }

    @Test
    fun `Simple enum type def`() {
        val sdl = "enum Enum { V1, V2 }"
        assertRoundTrip(sdl, "")
    }

    @Test
    fun `Simple input type`() {
        val sdl = "input Simple { i: Int }"
        assertRoundTrip(sdl)
    }

    @Test
    fun `Non-null wrapper`() {
        val sdl = "input Simple { i: Int! }"
        assertRoundTrip(sdl)
    }

    @Test
    fun `List wrapper`() {
        val sdl = "input Simple { i: [Int] }"
        assertRoundTrip(sdl)
    }

    @Test
    fun `Many combo of non-null and list wrappers`() {
        val sdl = """
            input Simple {
                ll: [[Int]]
                lbl: [[Int]]!
                lblb: [[Int]!]!
                lblbb: [[Int!]!]!
                lblnb: [[Int!]]!
                lllll: [[[[[Int]]]]]
                bbbbbb: [[[[[Int!]!]!]!]!]!
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `List depth 3 with all-nullable pattern - single word encoding`() {
        val sdl = """
            input Simple {
                lll: [[[Int]]]
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `List depth 3 with mixed nullability - two word encoding`() {
        val sdl = """
            input Simple {
                lllb: [[[Int]]]!
                llbl: [[[Int]]!]
                llbb: [[[Int]]!]!
                lbll: [[[Int]!]]
                lblb: [[[Int]!]]!
                lbbl: [[[Int]!]!]
                lbbb: [[[Int]!]!]!
                bllb: [[[Int!]]]!
                blbl: [[[Int!]]!]
                blbb: [[[Int!]]!]!
                bbll: [[[Int!]!]]
                bblb: [[[Int!]!]]!
                bbbl: [[[Int!]!]!]
                bbbb: [[[Int!]!]!]!
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `List depth 4 - two word encoding`() {
        val sdl = """
            input Simple {
                llll: [[[[Int]]]]
                llllb: [[[[Int]]]]!
                bbbb: [[[[Int!]!]!]!]!
                mixed: [[[[Int]!]]!]
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `List depth 5 and 6 - two word encoding`() {
        val sdl = """
            input Simple {
                depth5: [[[[[Int]]]]]
                depth5b: [[[[[Int!]!]!]!]!]!
                depth6: [[[[[[Int]]]]]]
                depth6b: [[[[[[Int!]!]!]!]!]!]!
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `List depth 10 - extreme depth`() {
        val sdl = """
            input Simple {
                veryDeep: [[[[[[[[[[Int]]]]]]]]]]
                veryDeepNonNull: [[[[[[[[[[Int!]!]!]!]!]!]!]!]!]!]!
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

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
