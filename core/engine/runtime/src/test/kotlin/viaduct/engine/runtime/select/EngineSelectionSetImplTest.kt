package viaduct.engine.runtime.select

import graphql.language.AstPrinter
import graphql.language.Field
import graphql.language.FragmentSpread
import graphql.language.InlineFragment
import graphql.language.SelectionSet as GJSelectionSet
import graphql.language.TypeName
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.Coordinate
import viaduct.engine.api.EngineSelection
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.mocks.MockSchema
import viaduct.engine.api.select.SelectionsParser

class EngineSelectionSetImplTest {
    private val defaultSdl =
        """
            type Struct { int: Int }

            enum Bar { A }

            type Foo implements Node {
              id: ID!
              int: Int
              foo: Foo
              bar: Bar
              struct: Struct
            }

            union FooOrStruct = Foo | Struct
            union FooUnion = Foo

            type Baz implements Node { id: ID! }
        """

    private fun mk(
        typename: String,
        selections: String,
        sdl: String = defaultSdl,
        vars: Map<String, Any?> = mapOf(),
    ): EngineSelectionSetImpl =
        MockSchema.mk(sdl).let { schema ->
            EngineSelectionSetImpl.create(
                SelectionsParser.parse(typename, selections),
                vars,
                schema,
            )
        }

    @Test
    fun `create -- simple fieldset`() {
        val ss = mk("Foo", "id")
        assertEquals(setOf("id"), ss.typeFields.keys)
    }

    @Test
    fun `create -- nested fieldset`() {
        val ss = mk("Foo", "id foo { int }")
        assertEquals(setOf("id", "foo"), ss.typeFields.keys)
    }

    @Test
    fun `create -- aliased fields`() {
        val ss = mk("Foo", "a: id, b: id")
        assertEquals(setOf("id"), ss.typeFields.keys)
    }

    @Test
    fun `create -- conditional field`() {
        val ss = mk("Node", "id @skip(if:true)")
        assertTrue(ss.typeFields.isEmpty())
    }

    @Test
    fun `create -- conditional fields with bound variables`() {
        var ss = mk("Foo", "id @skip(if:${'$'}skipIf)", vars = mapOf("skipIf" to true))
        assertEquals(setOf<String>(), ss.typeFields.keys)

        ss = mk("Foo", "id @skip(if:${'$'}skipIf)", vars = mapOf("skipIf" to false))
        assertEquals(setOf("id"), ss.typeFields.keys)

        ss = mk("Foo", "id @include(if:${'$'}includeIf)", vars = mapOf("includeIf" to true))
        assertEquals(setOf("id"), ss.typeFields.keys)

        ss = mk("Foo", "id @include(if:${'$'}includeIf)", vars = mapOf("includeIf" to false))
        assertEquals(setOf<String>(), ss.typeFields.keys)
    }

    @Test
    fun `create -- conditional fields with unbound variables`() {
        var ss = mk("Foo", "id @skip(if:${'$'}skipIf)")
        assertEquals(setOf("id"), ss.typeFields.keys)

        ss = mk("Foo", "id @skip(if:${'$'}skipIf)")
        assertEquals(setOf("id"), ss.typeFields.keys)

        ss = mk("Foo", "id @include(if:${'$'}includeIf)")
        assertEquals(setOf("id"), ss.typeFields.keys)

        ss = mk("Foo", "id @include(if:${'$'}includeIf)")
        assertEquals(setOf("id"), ss.typeFields.keys)
    }

    @Test
    fun `create -- contradictory skip and include with same variable are pruned`() {
        val ss = mk("Foo", "... @skip(if:${'$'}v) { ... @include(if:${'$'}v) { id } }")
        assertFalse(ss.containsSelection("Foo", "id"))
    }

    @Test
    fun `create -- unsatisfiable type condition is pruned`() {
        // Foo and Baz both implement Node but are siblings — Baz selections on Foo are unreachable
        val ss = mk(
            "Foo",
            "... on Node { ... on Baz { id } }",
        )
        assertFalse(ss.containsField("Foo", "id"))
    }

    @Test
    fun `addVariables -- unused variables`() {
        val ss1 = mk("Foo", "id")
        val ss2 = ss1.addVariables(mapOf("var" to true))
        assertEquals(ss1.selections(), ss2.selections())
    }

    @Test
    fun `addVariables -- re-evaluates constraints for fields`() {
        val ss = mk("Foo", "id @skip(if:\$var)")
        assertTrue(ss.addVariables(mapOf("var" to true)).isEmpty())
    }

    @Test
    fun `addVariables -- re-evaluates constraints for inline fragments`() {
        val ss = mk("Foo", "... @skip(if:\$var) { id }")
        assertTrue(ss.addVariables(mapOf("var" to true)).isEmpty())
    }

    @Test
    fun `addVariables -- re-evaluates constraints for fragment spreads`() {
        val ss = mk(
            "Foo",
            """
                fragment Main on Foo { ...Frag @skip(if:${'$'}var) }
                fragment Frag on Foo { id }
            """.trimIndent(),
        )
        assertTrue(ss.addVariables(mapOf("var" to true)).isEmpty())
    }

    @Test
    fun `toSelectionSet -- culls fields whose subselections are entirely pruned`() {
        mk("Query", "x q { x @skip(if:true) }", sdl = "extend type Query { x:Int q:Query }").let {
            assertEquals(
                """
                {
                  ... on Query {
                    x
                  }
                }
                """.trimIndent(),
                AstPrinter.printAst(it.toSelectionSet())
            )
        }
    }

    @Test
    fun `toSelectionSet -- culls unreachable subselections with unbound contradictory variables`() {
        val ss = mk(
            "Query",
            """
                q @skip(if: ${'$'}var) {
                  a: x
                  b: x @include(if: ${'$'}var)
                }
            """.trimIndent(),
            "extend type Query { x:Int q:Query }"
        )
        assertEquals(
            """
            {
              ... on Query {
                q @skip(if: ${'$'}var) {
                  a: x
                }
              }
            }
            """.trimIndent(),
            AstPrinter.printAst(ss.toSelectionSet())
        )
    }

    @Test
    fun `create -- pivots`() {
        val ss = mk(
            "Node",
            """
                id
                ... on Foo { int }
                ... on Baz { __typename }
            """
        )
        assertEquals(setOf("id"), ss.typeFields.keys)
    }

    @Test
    fun `create -- nested same-or-wider inline fragments`() {
        val ss = mk(
            "Foo",
            """
                ... on Foo {
                  ... on Node {
                    id
                    ... on Foo {
                      int
                    }
                  }
                }
            """
        )
        assertEquals(setOf("id", "int"), ss.typeFields.keys)
    }

    @Test
    fun `create -- self fragment`() {
        val ss = mk("Node", "... on Node { id }")
        assertEquals(setOf("id"), ss.typeFields.keys)
    }

    @Test
    fun `create -- conditional self fragment`() {
        val ss = mk("Node", "... on Node @skip(if:true) { id }")
        assertTrue(ss.typeFields.isEmpty())
    }

    @Test
    fun `create -- conditionless fragment`() {
        val ss = mk("Node", "... { id }")
        assertEquals(setOf("id"), ss.typeFields.keys)
    }

    @Test
    fun `create -- conditional conditionless fragment`() {
        val ss = mk("Node", "... @skip(if:true) { id }")
        assertEquals(setOf<String>(), ss.typeFields.keys)
    }

    @Test
    fun `create -- cyclic fragment spreads`() {
        assertThrows<IllegalArgumentException> {
            mk(
                "Node",
                """
                    fragment A on Node { ... B }
                    fragment B on Node { ... A }
                    fragment Main on Node { ... A }
                """.trimIndent()
            )
        }
    }

    @Test
    fun `create -- missing fragment definition`() {
        assertThrows<IllegalArgumentException> {
            mk("Node", "... MissingFragment")
        }
    }

    @Test
    fun `containsField -- simple`() {
        val ss = mk("Foo", "id")
        assertTrue(ss.containsField("Foo", "id"))
        assertFalse(ss.containsField("Foo", "int"))
    }

    @Test
    fun `containsField -- union`() {
        val ss = mk(
            "FooOrStruct",
            """
                __typename
                ... on Foo { id }
                ... on Struct { int }
            """
        )
        assertTrue(ss.containsField("FooOrStruct", "__typename"))

        assertTrue(ss.containsField("Foo", "id"))
        assertFalse(ss.containsField("Foo", "int"))
        // parent selections are inherited
        assertTrue(ss.containsField("Foo", "__typename"))

        assertTrue(ss.containsField("Struct", "int"))
        // parent selections are inherited
        assertTrue(ss.containsField("Struct", "__typename"))
    }

    @Test
    fun `containsField -- interface`() {
        val ss = mk(
            "Node",
            """
                id
                ... on Foo { bar }
                ... on Baz { __typename }
            """
        )

        assertTrue(ss.containsField("Node", "id"))

        assertTrue(ss.containsField("Foo", "bar"))
        // parent selections are inherited
        assertTrue(ss.containsField("Foo", "id"))

        assertTrue(ss.containsField("Baz", "__typename"))
        // parent selections are inherited
        assertTrue(ss.containsField("Baz", "id"))
    }

    @Test
    fun `containsField -- untyped inline fragment inherits enclosing type condition`() {
        val ss = mk("Node", "... on Foo { ... { int } }")
        assertTrue(ss.containsField("Foo", "int"))
        assertFalse(ss.containsField("Node", "int"))
    }

    @Test
    fun `containsField -- simple type projections do not change contained fields`() {
        val ss = mk("Node", "id ... on Foo { bar }")

        fun test(ss: EngineSelectionSet) {
            assertTrue(ss.containsField("Node", "id"))
            assertTrue(ss.containsField("Foo", "id"))
            assertTrue(ss.containsField("Foo", "bar"))
            assertFalse(ss.containsField("Foo", "__typename"))
        }

        ss.also(::test)
            .selectionSetForType("Foo").also(::test)
            .selectionSetForType("Node").also(::test)
    }

    @Test
    fun `containsField -- projecting into sibling types prunes fields`() {
        val ss = mk("Foo", "id bar")
            .selectionSetForType("Node")
            .selectionSetForType("Baz")

        assert(ss.isEmpty())
    }

    @Test
    fun `containsSelection -- empty`() {
        mk("Query", "__typename @skip(if:true)", sdl = "extend type Query { x: Int }").let {
            // valid field but not selected
            assertFalse(it.containsSelection("Query", "x"))

            // unselected alias
            assertFalse(it.containsSelection("Query", "alias"))
        }
    }

    @Test
    fun `containsSelection -- fields and aliases`() {
        val sdl = "extend type Query { x: Int }"

        // unaliased
        mk("Query", "x", sdl).let {
            assertTrue(it.containsSelection("Query", "x"))
            assertFalse(it.containsSelection("Query", "a"))
        }
        // aliased
        mk("Query", "a:x", sdl).let {
            assertFalse(it.containsSelection("Query", "x"))
            assertTrue(it.containsSelection("Query", "a"))
        }
    }

    @Test
    fun `containsSelection -- type conditions`() {
        val sdl = """
            extend type Query { empty: Int }
            interface Iface { x: Int }
            type Foo implements Iface { x: Int, y: Int }
        """.trimIndent()

        // narrowing
        mk("Iface", "a:x, ... on Foo {b:y}", sdl).let {
            assertTrue(it.containsSelection("Iface", "a"))
            assertTrue(it.containsSelection("Foo", "b"))
        }
    }

    @Test
    fun `plus SelectionSet -- empty`() {
        val ss = mk("Node", "__typename @skip(if:true)")
        assertEquals(ss, ss + GJSelectionSet(listOf()))
    }

    @Test
    fun `plus SelectionSet -- field`() {
        val ss = mk("Node", "__typename @skip(if:true)") +
            GJSelectionSet(listOf(Field("id")))

        assertEquals(setOf("id"), ss.typeFields.keys)
    }

    @Test
    fun `plus SelectionSet -- self inline fragment`() {
        val ss = mk("Node", "__typename @skip(if:true)") +
            GJSelectionSet(
                listOf(
                    InlineFragment(
                        TypeName("Node"),
                        GJSelectionSet(listOf(Field("id")))
                    )
                )
            )

        assertEquals(setOf("id"), ss.typeFields.keys)
    }

    @Test
    fun `plus SelectionSet -- conditionless inline fragment`() {
        val ss = mk("Node", "__typename @skip(if:true)") +
            GJSelectionSet(
                listOf(
                    InlineFragment(
                        null,
                        GJSelectionSet(listOf(Field("id")))
                    )
                )
            )

        assertEquals(setOf("id"), ss.typeFields.keys)
    }

    @Test
    fun `plus SelectionSet -- wider interface inline fragment`() {
        val ss = mk("Foo", "__typename @skip(if:true)") +
            GJSelectionSet(
                listOf(
                    InlineFragment(
                        TypeName("Node"),
                        GJSelectionSet(listOf(Field("id")))
                    )
                )
            )

        assertEquals(setOf("id"), ss.typeFields.keys)
    }

    @Test
    fun `plus SelectionSet -- wider union inline fragment`() {
        val ss = mk("Foo", "__typename @skip(if:true)") +
            GJSelectionSet(
                listOf(
                    InlineFragment(
                        TypeName("FooOrStruct"),
                        GJSelectionSet(listOf(Field("__typename")))
                    )
                )
            )

        assertEquals(setOf("__typename"), ss.typeFields.keys)
    }

    @Test
    fun `plus SelectionSet -- union member inline fragment`() {
        val ss = mk("FooOrStruct", "__typename @skip(if:true)") +
            GJSelectionSet(
                listOf(
                    InlineFragment(
                        TypeName("Foo"),
                        GJSelectionSet(listOf(Field("id")))
                    )
                )
            )

        assertEquals(setOf("id"), ss.selectionSetForType("Foo").typeFields.keys)
    }

    @Test
    fun `plus SelectionSet -- interface impl inline fragment`() {
        val ss = mk("Node", "__typename @skip(if:true)") +
            GJSelectionSet(
                listOf(
                    InlineFragment(
                        TypeName("Foo"),
                        GJSelectionSet(listOf(Field("id")))
                    )
                )
            )

        assertEquals(setOf("id"), ss.selectionSetForType("Foo").typeFields.keys)
    }

    @Test
    fun `plus SelectionSet -- self fragment spread`() {
        val ss = mk(
            "Node",
            """
                fragment Frag on Node { id }
                fragment Main on Node { __typename @skip(if:true) }
            """
        ) + GJSelectionSet(listOf(FragmentSpread("Frag")))

        assertEquals(setOf("id"), ss.typeFields.keys)
    }

    @Test
    fun `plus SelectionSet -- parent interface fragment spread`() {
        val ss = mk(
            "Foo",
            """
                fragment Frag on Node { id }
                fragment Main on Foo { __typename @skip(if:true) }
            """
        ) + GJSelectionSet(listOf(FragmentSpread("Frag")))

        assertEquals(setOf("id"), ss.typeFields.keys)
    }

    @Test
    fun `plus SelectionSet -- union member fragment spread`() {
        val ss = mk(
            "FooOrStruct",
            """
                fragment Frag on Foo { id }
                fragment Main on FooOrStruct { __typename @skip(if:true) }
            """
        ) + GJSelectionSet(listOf(FragmentSpread("Frag")))

        assertEquals(emptySet<String>(), ss.typeFields.keys)
        assertEquals(setOf("id"), ss.selectionSetForType("Foo").typeFields.keys)
    }

    @Test
    fun `plus SelectionSet -- interface impl fragment spread`() {
        val ss = mk(
            "Node",
            """
                fragment Frag on Foo { id }
                fragment Main on Node { __typename @skip(if:true) }
            """
        ) + GJSelectionSet(listOf(FragmentSpread("Frag")))

        assertEquals(emptySet<String>(), ss.typeFields.keys)
        assertEquals(setOf("id"), ss.selectionSetForType("Foo").typeFields.keys)
    }

    @Test
    fun `requestsType -- simple object`() {
        val ss = mk("Foo", "int")
        assertTrue(ss.requestsType("Foo"))
    }

    @Test
    fun `requestsType -- empty object`() {
        val ss = mk("Foo", "__typename @skip(if:true)")
        assertTrue(ss.requestsType("Foo"))
        assertTrue(ss.requestsType("Node"))
    }

    @Test
    fun `requestsType -- union narrowing`() {
        val ss = mk("FooOrStruct", "... on Foo { int }")
        // self
        assertTrue(ss.requestsType("Foo"))

        // union narrowing
        assertTrue(ss.requestsType("Foo"))
        assertFalse(ss.requestsType("Struct"))
    }

    @Test
    fun `requestsType -- union widening`() {
        val ss = mk("Foo", "int")

        // union widening
        assertTrue(ss.requestsType("FooUnion"))
        assertTrue(ss.requestsType("FooOrStruct"))

        // union sibling member
        assertFalse(ss.requestsType("Struct"))
    }

    @Test
    fun `requestsType -- interface`() {
        val ss = mk("Node", "... on Foo { int }")
        // self
        assertTrue(ss.requestsType("Node"))

        // interface narrowing
        assertTrue(ss.requestsType("Foo"))
        assertFalse(ss.requestsType("Baz"))
    }

    @Test
    fun `requestsType -- interface widening`() {
        val ss = mk("Foo", "int")
        // self
        assertTrue(ss.requestsType("Foo"))

        // interface widening
        assertTrue(ss.requestsType("Node"))

        // interface sibling impl
        assertFalse(ss.requestsType("Baz"))
    }

    @Test
    fun `requestsType -- simple type projections do not change requested types`() {
        val ss = mk("Foo", "__typename")

        fun test(ss: EngineSelectionSet) {
            assertTrue(ss.requestsType("Node"))
            assertTrue(ss.requestsType("Foo"))
            assertFalse(ss.requestsType("Baz"))
        }

        ss.also(::test)
            .selectionSetForType("Node").also(::test)
            .selectionSetForType("Foo").also(::test)
    }

    @Test
    fun `requestsType -- deeply nested type condition`() {
        fun test(nodeSelections: String) {
            assertTrue(
                mk("Node", nodeSelections, vars = mapOf("skipIf" to false)).requestsType("Foo"),
                nodeSelections
            )
            assertTrue(
                mk("Node", nodeSelections, vars = mapOf("skipIf" to true)).requestsType("Foo"),
                nodeSelections
            )
        }
        val skip = "@skip(if:${'$'}skipIf)"

        test(
            """
                ... {
                  ... {
                    ... {
                      ... on Foo {
                        id $skip
                      }
                    }
                  }
                }
            """.trimIndent()
        )
    }

    @Test
    fun `requestsType -- empty type projection`() {
        val ss = mk("Node", "__typename @skip(if:true)")
            .selectionSetForType("Foo")

        assertTrue(ss.requestsType("Node"))
        assertFalse(ss.requestsType("Foo"))
    }

    @Test
    fun `requestsType -- subselecting an empty field`() {
        val ss = mk("Foo", "__typename @skip(if:true)")
            .selectionSetForField("Foo", "struct")

        assertFalse(ss.requestsType("Foo"))
        assertFalse(ss.requestsType("Struct"))
    }

    @Test
    fun `isEmpty`() {
        fun test(nodeSelections: String) {
            assertFalse(
                mk("Node", nodeSelections, vars = mapOf("skipIf" to false)).isEmpty(),
                nodeSelections
            )
            assertTrue(
                mk("Node", nodeSelections, vars = mapOf("skipIf" to true)).isEmpty(),
                nodeSelections
            )
        }

        val skip = "@skip(if:${'$'}skipIf)"

        test("id $skip")
        test("... on Node $skip { __typename }")
        test("... on Node { __typename $skip }")
        test(
            """
                fragment Frag on Foo { __typename }
                fragment Main on Node { ... Frag $skip }
            """.trimIndent()
        )
        test(
            """
                fragment Frag on Foo { __typename $skip }
                fragment Main on Node { ... Frag }
            """.trimIndent()
        )
    }

    @Test
    fun `isTransitivelyEmpty`() {
        fun test(fooSelections: String) {
            assertFalse(
                mk("Foo", fooSelections, vars = mapOf("skipIf" to false)).isTransitivelyEmpty(),
                fooSelections
            )
            assertTrue(
                mk("Foo", fooSelections, vars = mapOf("skipIf" to true)).isTransitivelyEmpty(),
                fooSelections
            )
        }

        val skip = "@skip(if:${'$'}skipIf)"

        // deep field
        test(
            """
               foo {
                 __typename $skip
               }
            """.trimIndent()
        )

        // deep inline fragment
        test(
            """
               foo {
                 ... on Foo $skip { __typename }
               }
            """.trimIndent()
        )

        // deep fragment spread
        test(
            """
                fragment Frag on Foo { __typename }
                fragment Main on Foo {
                   foo {
                     ... Frag $skip
                   }
                }
            """.trimIndent()
        )

        // deep fragment definition
        test(
            """
                fragment Frag on Foo { __typename $skip }
                fragment Main on Foo {
                   foo {
                     ... Frag
                   }
                }
            """.trimIndent()
        )

        // deep wider inline fragment
        test(
            """
               foo {
                 ... on Node {
                   ... on Foo {
                      __typename $skip
                   }
                 }
               }
            """.trimIndent()
        )

        // composite introspection fields
        assertFalse(
            mk("Query", "__type(name:\"Foo\") { __typename }").isTransitivelyEmpty()
        )
        assertTrue(
            mk("Query", "__type(name:\"Foo\") { __typename @skip(if:true)}").isTransitivelyEmpty()
        )
    }

    @Test
    fun `isTransitivelyEmpty -- NonNull wrapped composite type`() {
        val sdl = """
            type Bar { x: Int }
            type Container implements Node { id: ID!, bar: Bar! }
        """.trimIndent()

        val ss = mk("Node", "... on Container { bar { x @skip(if:\$skipIf) } }", sdl = sdl, vars = mapOf("skipIf" to true))
        assertTrue(ss.isTransitivelyEmpty())

        val projected = ss.selectionSetForType("Container")
        assertTrue(projected is ProjectedEngineSelectionSet)
        assertTrue(projected.isTransitivelyEmpty())
    }

    @Test
    fun `selectionSetForField -- empty object`() {
        assertTrue(
            mk("Foo", "__typename @skip(if:true)")
                .selectionSetForField("Foo", "foo")
                .isEmpty()
        )
    }

    @Test
    fun `selectionSetForField -- simple object`() {
        val ss = mk("Foo", "foo { int }")
            .selectionSetForField("Foo", "foo")

        assertEquals(setOf("int"), ss.typeFields.keys)
    }

    @Test
    fun `selectionSetForField -- merged selections`() {
        val ss = mk("Foo", "s1:foo { int } s2:foo { id }")
            .selectionSetForField("Foo", "foo")

        assertEquals(setOf("int", "id"), ss.typeFields.keys)
    }

    @Test
    fun `selectionSetForField -- throws for non-composite field type`() {
        fun test(fooField: String) {
            val ss = mk("Foo", "__typename @skip(if:true)")
            assertThrows<IllegalArgumentException> {
                ss.selectionSetForField("Foo", fooField)
            }
        }

        test("__typename") // built-in field
        test("int") // scalar
        test("bar") // enum
        test("unknown") // invalid field
    }

    @Test
    fun `selectionSetForField -- throws for unknown field`() {
        val ss = mk("Foo", "__typename @skip(if:true)")
        assertThrows<IllegalArgumentException> {
            ss.selectionSetForField("Foo", "unknown")
        }
    }

    @Test
    fun `selectionSetForField -- unrelated type with same fieldNames`() {
        val ss = mk(
            "Foo",
            "bar { x }",
            sdl = """
                extend type Query { placeholder: Int }
                type Bar { x: Int }
                type Foo { bar: Bar }
                type Foo2 { bar: Bar }
            """
        )
        assertThrows<IllegalArgumentException> {
            ss.selectionSetForField("Foo2", "bar")
        }
    }

    @Test
    fun `selectionSetForField -- union member`() {
        val ss = mk(
            "FooOrStruct",
            """
                fragment Main on FooOrStruct {
                  __typename
                  ... on Foo {
                    struct { int }
                  }
                }
            """
        ).selectionSetForField("Foo", "struct")

        assertEquals(setOf("int"), ss.typeFields.keys)
    }

    @Test
    fun `selectionSetForField -- interface impl`() {
        val ss = mk(
            "Node",
            """
                fragment Main on Node {
                  ... on Foo {
                    struct { int }
                  }
                }
            """
        ).selectionSetForField("Foo", "struct")

        assertEquals(setOf("int"), ss.typeFields.keys)
    }

    @Test
    fun `selectionSetForField -- multiple fragments`() {
        val ss = mk(
            "Node",
            """
                fragment FooFrag on Foo { foo { id } }
                fragment Main on Node {
                  ... on Foo {
                    foo { int }
                  }
                  ... on Foo {
                    foo { bar }
                  }
                  ... FooFrag
                }
            """
        ).selectionSetForField("Foo", "foo")

        assertEquals(setOf("id", "int", "bar"), ss.typeFields.keys)
    }

    @Test
    fun `selectionSetForField -- interface widening`() {
        val ss = mk(
            "Foo",
            """
                fragment Main on Foo {
                  bar { x }
                }
            """,
            sdl = """
                extend interface Node { bar: Bar }
                type Foo implements Node { id: ID!, bar: Bar }
                type Bar { x: Int, y: Int }
            """
        )

        // Node.bar has no selections because selections have a narrower type condition on Foo
        assertTrue(ss.selectionSetForField("Node", "bar").isEmpty())
    }

    @Test
    fun `selectionSetForField -- interface field merging`() {
        val ss = mk(
            "Node",
            """
                fragment Main on Node {
                  bar { x }
                  ... on Foo {
                    bar { y }
                  }
                }
                """,
            sdl = """
                extend interface Node { bar: Bar }
                type Foo implements Node { id: ID!, bar: Bar }
                type Bar { x: Int, y: Int }
            """
        )

        // when subselecting Node.bar, selections that are gated by Foo type condition should be dropped
        assertEquals(
            setOf("x"),
            ss.selectionSetForField("Node", "bar").typeFields.keys
        )

        // when subselecting Foo.bar, selections should be the merged set of parent and child selections
        assertEquals(
            setOf("x", "y"),
            ss.selectionSetForField("Foo", "bar").typeFields.keys
        )
    }

    @Test
    fun `selectionSetForField -- abstract-abstract interface spreads`() {
        // Even though HasBar does not implement Node, it is a valid spread in a Node scope because of
        // the existence of Foo, which implements both Node and HasBar
        // see: https://spec.graphql.org/draft/#sec-Abstract-Spreads-in-Abstract-Scope
        val ss = mk(
            "Node",
            "... on HasBar { bar { int } }",
            sdl = """
                extend type Query { placeholder: Int }

                type Bar { int: Int }
                interface HasBar { bar: Bar }
                type Foo implements Node & HasBar { id: ID!, bar: Bar }
            """
        )

        // narrowings on either HasBar or Foo should both include bar.int
        assertEquals(setOf("int"), ss.selectionSetForField("Foo", "bar").typeFields.keys)
        assertEquals(setOf("int"), ss.selectionSetForField("HasBar", "bar").typeFields.keys)
    }

    @Test
    fun `selectionSetForField -- recursive field traversal`() {
        var ss = mk(
            "Foo",
            """
                id
                foo {
                    int
                    foo {
                        bar
                    }
                }
            """.trimIndent()
        )

        assertEquals(setOf("id", "foo"), ss.typeFields.keys)
        ss = ss.selectionSetForField("Foo", "foo")
        assertEquals(setOf("int", "foo"), ss.typeFields.keys)
        ss = ss.selectionSetForField("Foo", "foo")
        assertEquals(setOf("bar"), ss.typeFields.keys)
    }

    @Test
    fun `selectionSetForField -- deeply nested field`() {
        val ss = mk(
            "Node",
            """
                ... {
                  ... {
                    ... on Foo {
                      foo {
                        bar
                      }
                    }
                  }
                }
            """
        )

        assertEquals(
            setOf("bar"),
            ss.selectionSetForField("Foo", "foo").typeFields.keys
        )
    }

    @Test
    fun `selectionSetForField -- composite introspection fields`() {
        val ss = mk(
            "Query",
            """
                __type(name:"Foo") { __typename }
            """
        )

        assertEquals(
            setOf("__typename"),
            ss.selectionSetForField("Query", "__type").typeFields.keys
        )
    }

    @Test
    fun `selectionSetForSelection -- empty`() {
        mk("Query", "__typename @if(skip:true)", "extend type Query { x: Query }").let {
            assertThrows<IllegalArgumentException> {
                it.selectionSetForSelection("Query", "x")
            }
        }
    }

    @Test
    fun `selectionSetForSelection -- invalid`() {
        mk("Query", "x", "extend type Query { x: Int }").let {
            assertThrows<IllegalArgumentException> {
                it.selectionSetForSelection("Query", "x")
            }
        }
    }

    @Test
    fun `selectionSetForSelection -- subselect field`() {
        val sdl = "extend type Query { x: Int, y: Int, q: Query }"
        mk("Query", "q { x }, u:q { y }", sdl)
            .selectionSetForSelection("Query", "q")
            .let {
                assertTrue(it.containsSelection("Query", "x"))
                assertFalse(it.containsSelection("Query", "y"))
            }
    }

    @Test
    fun `selectionSetForSelection -- subselect alias`() {
        val sdl = "extend type Query { x: Int, y: Int, q: Query }"
        mk("Query", "a:q { x }, b:q { y }", sdl)
            .also {
                it.selectionSetForSelection("Query", "a").let {
                    assertTrue(it.containsSelection("Query", "x"))
                    assertFalse(it.containsSelection("Query", "y"))
                    assertFalse(it.containsSelection("Query", "a"))
                    assertFalse(it.containsSelection("Query", "b"))
                }
            }
            .also {
                it.selectionSetForSelection("Query", "b").let {
                    assertFalse(it.containsSelection("Query", "x"))
                    assertTrue(it.containsSelection("Query", "y"))
                    assertFalse(it.containsSelection("Query", "a"))
                    assertFalse(it.containsSelection("Query", "b"))
                }
            }
    }

    @Test
    fun `selectionSetForSelection -- type conditions`() {
        val sdl = """
            extend type Query { empty: Int }
            interface Iface { x: Iface }
            type Foo implements Iface { x: Iface, y: Iface }
        """.trimIndent()

        mk("Iface", "a:x { aa:__typename }, ... on Foo { b:y { bb:__typename } }", sdl).let {
            it.selectionSetForSelection("Foo", "b").let {
                assertTrue(it.containsSelection("Foo", "bb"))
                assertFalse(it.containsSelection("Foo", "__typename"))
            }

            it.selectionSetForSelection("Iface", "a").let {
                assertTrue(it.containsSelection("Iface", "aa"))
                assertFalse(it.containsSelection("Iface", "__typename"))
            }
        }
        // subselection merging
        mk("Iface", "x {a: __typename}, ... on Foo { x {b: __typename }}", sdl).let {
            // type condition Foo includes same-or-wider sub selections
            it.selectionSetForSelection("Foo", "x").let {
                assertTrue(it.containsSelection("Iface", "a"))
                assertTrue(it.containsSelection("Iface", "b"))
            }

            // type condition Iface does not include narrowing sub selectionsl
            it.selectionSetForSelection("Iface", "x").let {
                assertTrue(it.containsSelection("Iface", "a"))
                assertFalse(it.containsSelection("Iface", "b"))
            }
        }
    }

    @Test
    fun `selectionSetForSelection -- composite introspection fields`() {
        val ss = mk(
            "Query",
            """
                a:__type(name:"Foo") { __typename }
            """
        )

        assertEquals(
            setOf("__typename"),
            ss.selectionSetForSelection("Query", "a").typeFields.keys
        )
    }

    @Test
    fun `selectionSetForType -- simple object`() {
        val ss = mk("Foo", "int").selectionSetForType("Foo")
        assertEquals(setOf("int"), ss.typeFields.keys)
    }

    @Test
    fun `selectionSetForType -- narrowing union`() {
        val ss = mk(
            "FooOrStruct",
            "__typename ... on Foo { id }"
        )
        assertEquals(ss, ss.selectionSetForType("FooOrStruct"))

        assertEquals(setOf("__typename", "id"), ss.selectionSetForType("Foo").typeFields.keys)
        assertEquals(setOf("__typename"), ss.selectionSetForType("Struct").typeFields.keys)
    }

    @Test
    fun `selectionSetForType -- narrowing interface`() {
        val ss = mk(
            "Node",
            "id ... on Foo { int }"
        )
        assertEquals(ss, ss.selectionSetForType("Node"))

        assertEquals(setOf("id", "int"), ss.selectionSetForType("Foo").typeFields.keys)
        assertEquals(setOf("id"), ss.selectionSetForType("Baz").typeFields.keys)
    }

    @Test
    fun `selectionSetForType -- widen to interface`() {
        val ss = mk(
            "Foo",
            "int ... on Node { id }"
        )
        assertEquals(setOf("id"), ss.selectionSetForType("Node").typeFields.keys)
    }

    @Test
    fun `selectionSetForType -- widen to union`() {
        val ss = mk(
            "Foo",
            "int ... on FooOrStruct { __typename }"
        )
        assertEquals(setOf("__typename"), ss.selectionSetForType("FooOrStruct").typeFields.keys)
    }

    @Test
    fun `selectionSetForType -- abstract-abstract interface spreads`() {
        // Even though AbstractFoo does not implement Node, it is a valid spread in a Node scope because of
        // the existence of Foo, which implements both Node and AbstractFoo
        // see: https://spec.graphql.org/draft/#sec-Abstract-Spreads-in-Abstract-Scope
        val ss = mk(
            "Node",
            "... on AbstractFoo { x }",
            sdl = """
                extend type Query { placeholder: Int }

                interface AbstractFoo { x: Int }
                type Foo implements Node & AbstractFoo { id: ID!, x: Int }
            """
        )

        assertEquals(setOf("x"), ss.selectionSetForType("Foo").typeFields.keys)
        assertEquals(setOf("x"), ss.selectionSetForType("AbstractFoo").typeFields.keys)
    }

    @Test
    fun `selectionSetForType -- throws for unrelated type`() {
        val ss = mk("Foo", "__typename")
        assertThrows<IllegalArgumentException> {
            ss.selectionSetForType("Baz")
        }
    }

    @Test
    fun `selectionSetForType -- throws for non-composite type`() {
        val ss = mk("Foo", "__typename")
        assertThrows<IllegalArgumentException> {
            ss.selectionSetForType("ID")
        }
    }

    @Test
    fun `selectionSetForType -- deeply nested type condition`() {
        val ss = mk(
            "Node",
            """
                ... {
                  ... {
                    ... on Foo {
                      id
                    }
                    ... {
                      ... on Foo {
                        __typename
                      }
                    }
                  }
                }
            """.trimIndent()
        )

        assertEquals(
            setOf("id", "__typename"),
            ss.selectionSetForType("Foo").typeFields.keys
        )
    }

    @Test
    fun `selectionSetForType -- returns ProjectedEngineSelectionSet for concrete types`() {
        val projected = mk("Node", "id ... on Foo { int }").selectionSetForType("Foo")
        assertTrue(projected is ProjectedEngineSelectionSet, "expected ProjectedEngineSelectionSet, got ${projected::class.simpleName}")
        assertEquals("Foo", projected.type)
    }

    @Test
    fun `selectionSetForType -- identity optimization returns EngineSelectionSetImpl for same concrete type`() {
        val ss = mk("Foo", "id") // produces EngineSelectionSetImpl(def=Foo)
        assertSame(ss, ss.selectionSetForType("Foo")) // hits the identity check u == this.def and returns this
    }

    @Test
    fun `selectionSetForType -- abstract types return EngineSelectionSetImpl`() {
        val result = mk("Foo", "id ... on Node { __typename }").selectionSetForType("Node")
        assertTrue(result is EngineSelectionSetImpl, "expected EngineSelectionSetImpl for interface, got ${result::class.simpleName}")
    }

    @Test
    fun `selections -- simple`() {
        val ss = mk("Node", "id")
        assertEquals(
            listOf(EngineSelection("Node", "id", "id")),
            ss.selections()
        )
    }

    @Test
    fun `selections -- merged`() {
        val ss = mk("Node", "id ... on Node { id }")
        assertEquals(
            listOf(
                EngineSelection("Node", "id", "id"),
                EngineSelection("Node", "id", "id"),
            ),
            ss.selections()
        )
    }

    @Test
    fun `selections -- aliased`() {
        val ss = mk("Node", "alias: id")
        assertEquals(
            listOf(EngineSelection("Node", "id", "alias")),
            ss.selections()
        )
    }

    @Test
    fun `selections -- skip and include`() {
        mk("Node", "id @skip(if:true)").let {
            assertEquals(emptyList<EngineSelection>(), it.selections())
        }
        mk("Node", "id @include(if:false)").let {
            assertEquals(emptyList<EngineSelection>(), it.selections())
        }
    }

    @Test
    fun `selections -- interface`() {
        val ss = mk(
            "Node",
            """
                ... on Foo { int }
                ... on Baz { id }
            """.trimIndent()
        )
        assertEquals(
            setOf(
                EngineSelection("Foo", "int", "int"),
                EngineSelection("Baz", "id", "id"),
            ),
            ss.selections().toSet()
        )
    }

    @Test
    fun `selections -- abstract and concrete fields`() {
        val ss = mk(
            "Node",
            """
                id
                ... on Foo { id }
            """.trimIndent()
        )
        assertEquals(
            setOf(
                EngineSelection("Node", "id", "id"),
                EngineSelection("Foo", "id", "id"),
            ),
            ss.selections().toSet()
        )
    }

    @Test
    fun `traversableSelections -- excludes simple scalar fields`() {
        val ss = mk(
            "Query",
            "__typename, x, e",
            """
                enum E { x }
                extend type Query { x:Int, e:E }
            """.trimIndent()
        )
        assertEquals(emptyList<Coordinate>(), ss.traversableSelections())
    }

    @Test
    fun `traversableSelections -- excludes non-spreadable reprojections`() {
        val ss = mk(
            "Foo",
            """
                # widen
                ... on U {
                    # then narrow to a different type
                    ... on Bar { x }
                }
            """.trimIndent(),
            """
                type Foo { x:Int }
                type Bar { x:Int }
                union U = Foo | Bar
                extend type Query { u:U }
            """.trimIndent()
        )
        assertEquals(emptyList<Coordinate>(), ss.traversableSelections())
    }

    @Test
    fun `traversableSelections -- includes spreadable reprojections`() {
        val ss = mk(
            "Foo",
            """
                ... on FooOrStruct {
                  ... on Foo { foo { id } }
                }
            """.trimIndent()
        )
        assertEquals(
            listOf(EngineSelection("Foo", "foo", "foo")),
            ss.traversableSelections()
        )
    }

    @Test
    fun `traversableSelections -- includes wrapped composite types`() {
        val ss = mk(
            "Query",
            """
                fragment Main on Query {
                  o1 { x }
                  o2 { x }
                  o3 { x }
                  o4 { x }
                  s1, s2, s3, s4
                }
            """.trimIndent(),
            sdl = """
                type Obj { x:Int }
                extend type Query {
                    o1:Obj!
                    o2:[Obj]
                    o3:[Obj!]
                    o4:[Obj!]!

                    s1:Int!
                    s2:[Int]
                    s3:[Int!]
                    s4:[Int!]!
                }
            """.trimIndent()
        )
        assertEquals(
            setOf(
                EngineSelection("Query", "o1", "o1"),
                EngineSelection("Query", "o2", "o2"),
                EngineSelection("Query", "o3", "o3"),
                EngineSelection("Query", "o4", "o4"),
            ),
            ss.traversableSelections().toSet()
        )
    }

    @Test
    fun `traversableSelections -- self spreads`() {
        val ss = mk(
            "Foo",
            """
                ... {
                  foo { __typename }
                }
            """.trimIndent()
        )
        assertEquals(
            listOf(EngineSelection("Foo", "foo", "foo")),
            ss.traversableSelections()
        )
    }

    @Test
    fun `traversableSelections -- narrowing spreads`() {
        val ss = mk(
            "FooUnion",
            """
                ... on Foo { foo { id } }
            """.trimIndent()
        )
        assertEquals(
            listOf(EngineSelection("Foo", "foo", "foo")),
            ss.traversableSelections()
        )
    }

    @Test
    fun `argumentsOfSelection -- empty`() {
        val ss = mk("Query", "x @skip(if:true)", "extend type Query { x: Int }")
        assertEquals(null, ss.argumentsOfSelection("Query", "x"))
    }

    @Test
    fun `argumentsOfSelection -- no args`() {
        val ss = mk("Query", "x", "extend type Query { x: Int }")
        assertEquals(emptyMap<String, Any?>(), ss.argumentsOfSelection("Query", "x"))
    }

    @Test
    fun `argumentsOfSelection -- args without defaults`() {
        val ss = mk("Query", "x(y:2)", "extend type Query { x(y:Int):Int }")
        assertEquals(mapOf("y" to 2), ss.argumentsOfSelection("Query", "x"))
    }

    @Test
    fun `argumentsOfSelection -- args with variable`() {
        val ss = mk("Query", "x(y:\$yvar)", "extend type Query { x(y:Int):Int }", mapOf("yvar" to 2))
        assertEquals(mapOf("y" to 2), ss.argumentsOfSelection("Query", "x"))
    }

    @Test
    fun `argumentsOfSelection -- args with default value`() {
        val sdl = "extend type Query { x(y:Int = 2): Int }"

        // no selected value
        mk("Query", "x", sdl).let {
            assertEquals(mapOf("y" to 2), it.argumentsOfSelection("Query", "x"))
        }
        // explicit null
        mk("Query", "x(y:null)", sdl).let {
            assertEquals(mapOf("y" to null), it.argumentsOfSelection("Query", "x"))
        }
        // non-null value
        mk("Query", "x(y:3)", sdl).let {
            assertEquals(mapOf("y" to 3), it.argumentsOfSelection("Query", "x"))
        }
        // variable value
        mk("Query", "x(y:\$yvar)", sdl, mapOf("yvar" to 3)).let {
            assertEquals(mapOf("y" to 3), it.argumentsOfSelection("Query", "x"))
        }
    }

    @Test
    fun `argumentsOfSelection -- arg of input object`() {
        val sdl = """
            extend type Query { x(y: Input): Int }
            input Input { z: Int, input: Input }
        """.trimIndent()

        // explicit null
        mk("Query", "x(y:null)", sdl = sdl).let {
            assertEquals(mapOf("y" to null), it.argumentsOfSelection("Query", "x"))
        }
        // object
        mk("Query", "x(y:{z:1, input:{z:2}})", sdl = sdl).let {
            val exp = mapOf("y" to mapOf("z" to 1, "input" to mapOf("z" to 2)))
            assertEquals(exp, it.argumentsOfSelection("Query", "x"))
        }
        // variable value
        mk("Query", "x(y:{z:1, input:{z:\$varz}})", vars = mapOf("varz" to 2), sdl = sdl).let {
            val exp = mapOf("y" to mapOf("z" to 1, "input" to mapOf("z" to 2)))
            assertEquals(exp, it.argumentsOfSelection("Query", "x"))
        }
    }

    @Test
    fun `argumentsOfSelection -- arg of input object with defaults`() {
        val sdl = """
             extend type Query { x(y: Input = {z: 0, input: null}): Int }
            input Input { z: Int=1, input: Input }
        """.trimIndent()

        // no args
        mk("Query", "x", sdl = sdl).let {
            assertEquals(mapOf("y" to mapOf("z" to 0, "input" to null)), it.argumentsOfSelection("Query", "x"))
        }
        // partial input
        mk("Query", "x(y:{})", sdl = sdl).let {
            assertEquals(mapOf("y" to mapOf("z" to 1)), it.argumentsOfSelection("Query", "x"))
        }
    }

    @Test
    fun `argumentsOfSelection -- arg of list`() {
        mk("Query", "x(y: [[1], [2, 3]])", sdl = "extend type Query { x(y: [[Int]]): Int }").let {
            assertEquals(mapOf("y" to listOf(listOf(1), listOf(2, 3))), it.argumentsOfSelection("Query", "x"))
        }
    }

    @Test
    fun `argumentsOfSelection -- arg of list with defaults`() {
        val sdl = "extend type Query { x(y: [[Int]] = [[1], [2,3]]): Int }"
        // no args
        mk("Query", "x", sdl = sdl).let {
            assertEquals(mapOf("y" to listOf(listOf(1), listOf(2, 3))), it.argumentsOfSelection("Query", "x"))
        }
        // explicit nulls
        mk("Query", "x(y:null)", sdl = sdl).let {
            assertEquals(mapOf("y" to null), it.argumentsOfSelection("Query", "x"))
        }
        mk("Query", "x(y:[null])", sdl = sdl).let {
            assertEquals(mapOf("y" to listOf(null)), it.argumentsOfSelection("Query", "x"))
        }
        mk("Query", "x(y:[[null]])", sdl = sdl).let {
            assertEquals(mapOf("y" to listOf(listOf(null))), it.argumentsOfSelection("Query", "x"))
        }
        // value
        mk("Query", "x(y:[[-1]])", sdl = sdl).let {
            assertEquals(mapOf("y" to listOf(listOf(-1))), it.argumentsOfSelection("Query", "x"))
        }
    }

    @Test
    fun `argumentsOfSelection -- type conditions`() {
        val sdl = """
             extend type Query { empty: Int }
            interface Iface { x(z: Int): Int }
            type Foo implements Iface {
                x(z: Int): Int
                y(z: Int): Int
            }
        """.trimIndent()

        // narrowing type conditions
        mk("Iface", "a:x(z:1), ... on Foo { b:x(z:2), c:y(z:3) }", sdl = sdl).let {
            assertEquals(mapOf("z" to 1), it.argumentsOfSelection("Iface", "a"))
            assertEquals(mapOf("z" to 1), it.argumentsOfSelection("Foo", "a"))
            assertEquals(mapOf("z" to 2), it.argumentsOfSelection("Foo", "b"))
            assertEquals(mapOf("z" to 3), it.argumentsOfSelection("Foo", "c"))
        }
    }

    @Test
    fun `argumentsOfSelection -- arg of list of object with defaults`() {
        val sdl = """
             extend type Query { x(y:[Input] = [{z: 1, input: null}]): Int }
            input Input { z: Int, input: Input }
        """.trimIndent()

        // no args
        mk("Query", "x", sdl = sdl).let {
            assertEquals(mapOf("y" to listOf(mapOf("z" to 1, "input" to null))), it.argumentsOfSelection("Query", "x"))
        }
    }

    @Test
    fun `resolveSelection -- unaliased`() {
        mk("Query", "x", sdl = "extend type Query { x: Int }").let {
            assertEquals(
                EngineSelection("Query", "x", "x"),
                it.resolveSelection("Query", "x")
            )
        }
    }

    @Test
    fun `resolveSelection -- aliased`() {
        mk("Query", "y:x", sdl = "extend type Query { x: Int }").let {
            assertEquals(
                EngineSelection("Query", "x", "y"),
                it.resolveSelection("Query", "y")
            )
        }
    }

    @Test
    fun `resolveSelection -- type conditions`() {
        val sdl = """
             extend type Query { empty: Int }
            interface Iface { x: Int }
            type Foo implements Iface { x:Int, y: Int }
        """.trimIndent()

        mk("Iface", "x, ...on Foo {y, a:x}", sdl = sdl).let {
            // same
            assertEquals(
                EngineSelection("Iface", "x", "x"),
                it.resolveSelection("Iface", "x")
            )
            // narrower than
            assertEquals(
                EngineSelection("Foo", "y", "y"),
                it.resolveSelection("Foo", "y")
            )
            assertEquals(
                EngineSelection("Iface", "x", "x"),
                it.resolveSelection("Foo", "x")
            )
            assertEquals(
                EngineSelection("Foo", "x", "a"),
                it.resolveSelection("Foo", "a")
            )
        }
    }

    @Test
    fun `toSelectionSet -- empty`() {
        mk("Query", "x @skip(if:true)", sdl = "extend type Query { x:Int }").let {
            assertEquals("", AstPrinter.printAst(it.toSelectionSet()))
        }
    }

    @Test
    fun `toSelectionSet -- cull empty selections`() {
        mk("Query", "x q { x @skip(if:true) }", sdl = "extend type Query { x:Int q:Query }").let {
            assertEquals(
                """
                    {
                      ... on Query {
                        x
                      }
                    }
                """.trimIndent(),
                AstPrinter.printAst(it.toSelectionSet())
            )
        }
    }

    @Test
    fun `toSelectionSet -- unbound variables`() {
        mk("Query", "x @skip(if:\$skipIf)", sdl = "extend type Query {x:Int}").let {
            assertEquals(
                """
                    {
                      ... on Query {
                        x @skip(if: ${'$'}skipIf)
                      }
                    }
                """.trimIndent(),
                AstPrinter.printAst(it.toSelectionSet())
            )
        }
    }

    @Test
    fun `toSelectionSet -- fragment spreads`() {
        val sdl = "extend type Query { x:Int, q:Query }"
        val ss = """
            fragment Main on Query {
                x
                q {
                    a:x
                    ... F1
                }
            }
            fragment F1 on Query { b:x, ... F2 }
            fragment F2 on Query { c:x }
        """.trimIndent()
        mk("Query", ss, sdl = sdl).let {
            assertEquals(
                """
                    {
                      ... on Query {
                        x
                        q {
                          a: x
                          ... on Query {
                            b: x
                            ... on Query {
                              c: x
                            }
                          }
                        }
                      }
                    }
                """.trimIndent(),
                AstPrinter.printAst(it.toSelectionSet())
            )
        }
    }

    @Test
    fun `printAsFieldSet -- empty`() {
        mk("Query", "x @skip(if:true)", "extend type Query {x:Int}").let { ss ->
            assertEquals("", ss.printAsFieldSet())
        }
    }

    @Test
    fun `printAsFieldSet -- bound variables`() {
        mk("Query", "x @skip(if:\$var)", "extend type Query {x:Int}", mapOf("var" to false)).let { ss ->
            assertEquals("...on Query{x @skip(if:\$var)}", ss.printAsFieldSet())
        }
    }

    @Test
    fun `printAsFieldSet -- unbound variables`() {
        mk("Query", "x @skip(if:\$var)", "extend type Query {x:Int}").let { ss ->
            assertEquals("...on Query{x @skip(if:\$var)}", ss.printAsFieldSet())
        }
    }

    @Test
    fun `printAsFieldSet -- fragment spreads`() {
        val sdl = "extend type Query { x:Int, q:Query }"
        val ss = """
            fragment Main on Query {
                x
                q {
                    a:x
                    ... F1
                }
            }
            fragment F1 on Query { b:x, ... F2 }
            fragment F2 on Query { c:x }
        """.trimIndent()
        mk("Query", ss, sdl = sdl).let {
            assertEquals(
                "...on Query{x q{a:x ...on Query{b:x ...on Query{c:x}}}}",
                it.printAsFieldSet()
            )
        }
    }

    @Test
    fun `use case -- chained type projection`() {
        val ss = mk(
            "Int1",
            """
                ... on Int1 {
                  id1
                  ... on Int2 {
                    id2
                    ... on Obj {
                      __typename
                    }
                  }
                }
            """,
            sdl = """
                 extend type Query { placeholder: Int }

                interface Int1 { id1: ID }
                interface Int2 implements Int1 { id1: ID, id2: ID }
                type Obj implements Int1 & Int2 { id1: ID, id2: ID }
            """
        )

        ss
            .also { assertEquals(setOf("id1"), it.typeFields.keys) }
            .selectionSetForType("Int2")
            .also { assertEquals(setOf("id1", "id2"), it.typeFields.keys) }
            .selectionSetForType("Obj")
            .also { assertEquals(setOf("id1", "id2", "__typename"), it.typeFields.keys) }
    }

    @Test
    fun `use case -- chained field traversal`() {
        val ss = mk(
            "Foo",
            """
                a
                bar {
                  b
                  baz {
                    c
                    foo {
                      __typename
                    }
                  }
                }
            """,
            sdl = """
                 extend type Query { placeholder: Int }

                type Foo { a: Int, bar: Bar }
                type Bar { b: Int, baz: Baz }
                type Baz { c: Int, foo: Foo }
            """
        )

        ss
            .also { assertEquals(setOf("a", "bar"), it.typeFields.keys) }
            .selectionSetForField("Foo", "bar")
            .also { assertEquals(setOf("b", "baz"), it.typeFields.keys) }
            .selectionSetForField("Bar", "baz")
            .also { assertEquals(setOf("c", "foo"), it.typeFields.keys) }
            .selectionSetForField("Baz", "foo")
            .also { assertEquals(setOf("__typename"), it.typeFields.keys) }
            .selectionSetForField("Foo", "bar")
            .also { assertTrue(it.isEmpty()) }
    }

    @Test
    fun `use case -- mixed projections and traversal`() {
        // this test ties together some different rules:
        // - for interfaces with field types that support containers, selections on the
        //   interface type are merged into selections on the implementing type when
        //   projecting
        //
        // - selection sets that can re-visit types do not inherit the selections of the
        //   previous visit
        //
        // - interleaved field traversal and type projection is sane

        val ss = mk(
            "Fork",
            """
                struct { __typename }
                ... on Foo {
                  struct { x }
                  fork {
                    ... on Bar {
                      struct { y }
                    }
                  }
                }
                ... on Bar {
                  struct { y }
                  fork {
                    ... on Foo {
                      struct { x }
                    }
                  }
                }
            """,
            sdl = """
                 extend type Query { placeholder: Int }

                type Struct { x: Int, y: Int }
                interface Fork { struct: Struct }
                type Foo implements Fork { struct: Struct, fork: Fork }
                type Bar implements Fork { struct: Struct, fork: Fork }
            """
        )

        ss
            .also {
                assertEquals(
                    setOf("__typename"),
                    it.selectionSetForField("Fork", "struct").typeFields.keys
                )
            }
            .also {
                // descend into Foo fork
                it.selectionSetForType("Foo")
                    .also {
                        assertEquals(
                            setOf("__typename", "x"),
                            it.selectionSetForField("Foo", "struct").typeFields.keys
                        )
                    }
                    .selectionSetForField("Foo", "fork")
                    .also {
                        assertEquals(
                            emptySet<String>(),
                            it.selectionSetForField("Fork", "struct").typeFields.keys
                        )
                    }
                    .selectionSetForType("Bar")
                    .also {
                        assertEquals(
                            setOf("y"),
                            it.selectionSetForField("Bar", "struct").typeFields.keys
                        )
                    }
            }
            .also {
                // descend into Bar fork
                it.selectionSetForType("Bar")
                    .also {
                        assertEquals(
                            setOf("__typename", "y"),
                            it.selectionSetForField("Bar", "struct").typeFields.keys
                        )
                    }
                    .selectionSetForField("Bar", "fork")
                    .also {
                        assertEquals(
                            emptySet<String>(),
                            it.selectionSetForField("Fork", "struct").typeFields.keys
                        )
                    }
                    .selectionSetForType("Foo")
                    .also {
                        assertEquals(
                            setOf("x"),
                            it.selectionSetForField("Foo", "struct").typeFields.keys
                        )
                    }
            }
    }
}
