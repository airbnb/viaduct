package viaduct.engine.api.select

import graphql.language.AstPrinter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ParsedSelectionsImplTest {
    @Test
    fun `toDocument`() {
        // simple field set
        SelectionsParser.parse("Foo", "field").let { parsed ->
            val docString = parsed.toDocument().render()
            assertEquals(
                "fragment Main on Foo {field}",
                docString
            )
        }

        // multi-fragment document
        SelectionsParser.parse(
            "Foo",
            """
                fragment Main on Foo { field ...Other }
                fragment Other on Foo { field }
            """.trimIndent()
        ).let { parsed ->
            val docString = parsed.toDocument().render()
            assertEquals(
                "fragment Main on Foo {field ...Other} fragment Other on Foo {field}",
                docString
            )
        }
    }

    @Test
    fun `filterToPath -- empty path`() {
        val ps = SelectionsParser.parse("Query", "field")
        assertParsedSelectionsEqual(ps, ps.filterToPath(emptyList()))
    }

    @Test
    fun `filterToPath -- unselected segment`() {
        val ps = SelectionsParser.parse("Query", "foo")
        assertNull(ps.filterToPath(listOf("bar")))
    }

    @Test
    fun `filterToPath -- extra segments`() {
        val ps = SelectionsParser.parse("Query", "foo")
        assertNull(ps.filterToPath(listOf("foo", "bar")))
    }

    @Test
    fun `filterToPath -- simple`() {
        val parsed = SelectionsParser.parse(
            "Query",
            """
                fragment Main on Query {
                  a1 {
                    b1 { c1 c2 }
                    b2 { c3 c4 }
                  }
                  a2
                }
            """.trimIndent(),
        )
        val filtered = parsed
            .filterToPath(listOf("a1", "b2", "c4"))!!

        assertParsedSelectionsEqual(
            SelectionsParser.parse("Query", "a1 { b2 { c4 } }"),
            filtered
        )
        assertEquals(
            "fragment Main on Query {a1{b2{c4}}}",
            AstPrinter.printAstCompact(filtered.toDocument())
        )
    }

    @Test
    fun `filterToPath -- partial filtering`() {
        val parsed = SelectionsParser.parse(
            "Query",
            """
                fragment Main on Query {
                  a1 {
                    b1 { c1 c2 }
                    b2 { c3 c4 }
                  }
                  a2
                }
            """.trimIndent(),
        )
        val filtered = parsed.filterToPath(listOf("a1", "b2"))!!

        assertParsedSelectionsEqual(
            SelectionsParser.parse("Query", "a1 { b2 { c3 c4 } }"),
            filtered
        )
    }

    @Test
    fun `filterToPath -- fragmented docs`() {
        val parsed = SelectionsParser.parse(
            "Query",
            """
                fragment Main on Query { a { ... A } }
                fragment A on A { a1, a2, b { ...B } }
                fragment B on B { b1, b2 }
            """.trimIndent(),
        )
        val filtered = parsed.filterToPath(listOf("a", "b", "b1"))!!

        // fragment spreads are converted to inline fragments
        assertParsedSelectionsEqual(
            SelectionsParser.parse(
                "Query",
                """
                    a {
                      ... on A {
                        b {
                          ... on B {
                            b1
                          }
                        }
                      }
                    }
                """.trimIndent()
            ),
            filtered
        )
    }

    @Test
    fun `filterToPath -- partial filtering of fragmented docs -- unfiltered fragments are inlined`() {
        val parsed = SelectionsParser.parse(
            "Query",
            """
                fragment Main on Query { a { ... A } }
                fragment A on A { b { ... B } }
                fragment B on B { b1 }
            """.trimIndent()
        )
        val filtered = parsed.filterToPath(listOf("a"))
        assertParsedSelectionsEqual(
            SelectionsParser.parse(
                "Query",
                """
                    fragment Main on Query {
                        a {
                            ... on A {
                                b {
                                    ... on B { b1 }
                                }
                            }
                        }
                    }
                """.trimIndent(),
            ),
            filtered
        )
    }

    @Test
    fun `filterToPath -- field directives are preserved`() {
        val parsed = SelectionsParser.parse(
            "Query",
            "a @dir(a:1) { b @dir(b:2) }"
        )
        val filtered = parsed.filterToPath(listOf("a", "b"))!!
        assertParsedSelectionsEqual(parsed, filtered)
    }

    @Test
    fun `filterToPath -- fragment spread directives are mapped to directives on inline fragments`() {
        val parsed = SelectionsParser.parse(
            "Query",
            """
                fragment Main on Query { ... A @dir(foo:1) }
                fragment A on A { a1 }
            """.trimIndent()
        )
        val filtered = parsed.filterToPath(listOf("a1"))
        assertParsedSelectionsEqual(
            SelectionsParser.parse("Query", "... on A @dir(foo:1) { a1 }"),
            filtered
        )
    }

    @Test
    fun equals() {
        // not a ParsedSelections
        assertNotEquals(this, SelectionsParser.parse("Query", "x"))

        // different typename
        assertNotEquals(
            SelectionsParser.parse("A", "x"),
            SelectionsParser.parse("B", "x")
        )

        // different selections
        assertNotEquals(
            SelectionsParser.parse("A", "a"),
            SelectionsParser.parse("A", "b")
        )

        // different fragment names
        assertNotEquals(
            SelectionsParser.parse(
                "A",
                """
                    fragment Main on A { a }
                    fragment B on B { b }
                """.trimIndent()
            ),
            SelectionsParser.parse(
                "A",
                """
                    fragment Main on A { a }
                    fragment C on C { c }
                """.trimIndent()
            ),
        )

        // different fragment selections
        assertNotEquals(
            SelectionsParser.parse(
                "A",
                """
                    fragment Main on A { a }
                    fragment B on B { b1 }
                """.trimIndent()
            ),
            SelectionsParser.parse(
                "A",
                """
                    fragment Main on A { a }
                    fragment B on B { b2 }
                """.trimIndent()
            ),
        )

        // equals -- simple
        assertEquals(
            SelectionsParser.parse("A", "a"),
            SelectionsParser.parse("A", "a"),
        )

        // equals -- fragmented
        assertEquals(
            SelectionsParser.parse(
                "A",
                """
                    fragment Main on A { a }
                    fragment B on B { b1 }
                """.trimIndent()
            ),
            SelectionsParser.parse(
                "A",
                """
                    fragment Main on A { a }
                    fragment B on B { b1 }
                """.trimIndent()
            )
        )
    }
}
