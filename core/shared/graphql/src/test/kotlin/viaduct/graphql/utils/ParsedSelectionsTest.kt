package viaduct.graphql.utils

import graphql.language.AstPrinter
import graphql.parser.Parser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ParsedSelectionsTest {
    private fun parse(
        typeName: String,
        fragment: String
    ): ParsedSelections = ParsedSelections.fromDocument(typeName, Parser.parse(fragment))

    @Test
    fun `empty`() {
        val ps = ParsedSelections.empty("Query")
        assertEquals(0, ps.selections.selections.size)
        assertEquals(0, ps.fragmentMap.size)
        assertEquals(0, ps.toDocument().definitions.size)
        assertNull(ps.filterToPath(emptyList()))
    }

    @Test
    fun `fromDocument rejects entrypoint fragment on wrong type`() {
        assertThrows<IllegalArgumentException> {
            parse("Foo", "fragment Main on Bar { x }")
        }
    }

    @Test
    fun `fromDocument rejects duplicate fragment definitions`() {
        assertThrows<IllegalArgumentException> {
            parse(
                "Foo",
                """
                    fragment X on X { a }
                    fragment X on X { b }
                    fragment Main on Foo { c }
                """.trimIndent()
            )
        }
    }

    @Test
    fun `fromDocument rejects non-fragment definitions`() {
        assertThrows<IllegalArgumentException> {
            parse(
                "Query",
                """
                    fragment Query on Query { x }
                    query Q { ...Query }
                """.trimIndent()
            )
        }
    }

    @Test
    fun `toDocument`() {
        parse("Foo", "fragment Main on Foo { field }").let { parsed ->
            assertEquals(
                "fragment Main on Foo {field}",
                parsed.render()
            )
        }

        parse(
            "Foo",
            """
                fragment Main on Foo { field ...Other }
                fragment Other on Foo { field }
            """.trimIndent()
        ).let { parsed ->
            assertEquals(
                "fragment Main on Foo {field ...Other} fragment Other on Foo {field}",
                parsed.render()
            )
        }
    }

    @Test
    fun `filterToPath -- empty path`() {
        val ps = parse("Query", "fragment Main on Query { field }")
        assertParsedSelectionsEqual(ps, ps.filterToPath(emptyList()))
    }

    @Test
    fun `filterToPath -- unselected segment`() {
        val ps = parse("Query", "fragment Main on Query { foo }")
        assertNull(ps.filterToPath(listOf("bar")))
    }

    @Test
    fun `filterToPath -- extra segments`() {
        val ps = parse("Query", "fragment Main on Query { foo }")
        assertNull(ps.filterToPath(listOf("foo", "bar")))
    }

    @Test
    fun `filterToPath -- simple`() {
        val parsed = parse(
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
        val filtered = parsed.filterToPath(listOf("a1", "b2", "c4"))!!

        assertParsedSelectionsEqual(
            parse("Query", "fragment Main on Query { a1 { b2 { c4 } } }"),
            filtered
        )
        assertEquals(
            "fragment Main on Query {a1{b2{c4}}}",
            AstPrinter.printAstCompact(filtered.toDocument())
        )
    }

    @Test
    fun `filterToPath -- partial filtering`() {
        val parsed = parse(
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
            parse("Query", "fragment Main on Query { a1 { b2 { c3 c4 } } }"),
            filtered
        )
    }

    @Test
    fun `filterToPath -- fragmented docs`() {
        val parsed = parse(
            "Query",
            """
                fragment Main on Query { a { ... A } }
                fragment A on A { a1, a2, b { ...B } }
                fragment B on B { b1, b2 }
            """.trimIndent(),
        )
        val filtered = parsed.filterToPath(listOf("a", "b", "b1"))!!

        assertParsedSelectionsEqual(
            parse(
                "Query",
                """
                    fragment Main on Query {
                        a {
                          ... on A {
                            b {
                              ... on B {
                                b1
                              }
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
        val parsed = parse(
            "Query",
            """
                fragment Main on Query { a { ... A } }
                fragment A on A { b { ... B } }
                fragment B on B { b1 }
            """.trimIndent()
        )
        val filtered = parsed.filterToPath(listOf("a"))
        assertParsedSelectionsEqual(
            parse(
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
        val parsed = parse(
            "Query",
            "fragment Main on Query { a @dir(a:1) { b @dir(b:2) } }"
        )
        val filtered = parsed.filterToPath(listOf("a", "b"))!!
        assertParsedSelectionsEqual(parsed, filtered)
    }

    @Test
    fun `filterToPath -- fragment spread directives are mapped to directives on inline fragments`() {
        val parsed = parse(
            "Query",
            """
                fragment Main on Query { ... A @dir(foo:1) }
                fragment A on A { a1 }
            """.trimIndent()
        )
        val filtered = parsed.filterToPath(listOf("a1"))
        assertParsedSelectionsEqual(
            parse("Query", "fragment Main on Query { ... on A @dir(foo:1) { a1 } }"),
            filtered
        )
    }

    @Test
    fun `filterToPath -- aliased field`() {
        val parsed = parse("Query", "fragment Main on Query { myAlias: original { sub } }")
        assertNotNull(parsed.filterToPath(listOf("myAlias", "sub")))
        assertNull(parsed.filterToPath(listOf("original")))
    }

    @Test
    fun equals() {
        // not a ParsedSelections
        assertNotEquals(this, parse("Query", "fragment Main on Query { x }"))

        // different typename
        assertNotEquals(
            parse("A", "fragment Main on A { x }"),
            parse("B", "fragment Main on B { x }")
        )

        // different selections
        assertNotEquals(
            parse("A", "fragment Main on A { a }"),
            parse("A", "fragment Main on A { b }")
        )

        // different fragment names
        assertNotEquals(
            parse(
                "A",
                """
                    fragment Main on A { a }
                    fragment B on B { b }
                """.trimIndent()
            ),
            parse(
                "A",
                """
                    fragment Main on A { a }
                    fragment C on C { c }
                """.trimIndent()
            ),
        )

        // different fragment selections
        assertNotEquals(
            parse(
                "A",
                """
                    fragment Main on A { a }
                    fragment B on B { b1 }
                """.trimIndent()
            ),
            parse(
                "A",
                """
                    fragment Main on A { a }
                    fragment B on B { b2 }
                """.trimIndent()
            ),
        )

        // equals -- simple
        assertEquals(
            parse("A", "fragment Main on A { a }"),
            parse("A", "fragment Main on A { a }"),
        )

        // equals -- fragmented
        assertEquals(
            parse(
                "A",
                """
                    fragment Main on A { a }
                    fragment B on B { b1 }
                """.trimIndent()
            ),
            parse(
                "A",
                """
                    fragment Main on A { a }
                    fragment B on B { b1 }
                """.trimIndent()
            )
        )
    }
}

private fun ParsedSelections.render(): String = AstPrinter.printAstCompact(toDocument())

private fun assertParsedSelectionsEqual(
    expected: ParsedSelections,
    actual: ParsedSelections?
) {
    assertEquals(expected, actual, "Expected:\n$expected\nActual:\n$actual")
}
