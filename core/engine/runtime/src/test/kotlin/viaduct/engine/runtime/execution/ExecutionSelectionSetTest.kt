package viaduct.engine.runtime.execution

import graphql.language.AstPrinter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.Coordinate
import viaduct.engine.api.EngineSelection
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.mocks.MockSchema
import viaduct.engine.api.select.SelectionsParser
import viaduct.engine.runtime.select.typeFields

class ExecutionSelectionSetTest {
    private val defaultSdl =
        """
            directive @testDirective(flag: Boolean!, label: String) on FIELD

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

            extend type Query {
                x: Int
                foo(size: Int = 2): Foo
                union: FooOrStruct
            }
        """

    @Nested
    inner class Creation {
        @Test
        fun `conditional fields with unbound include remain`() {
            val selections = mk("Foo", "id @include(if: \$includeField)")

            assertTrue(selections.containsField("Foo", "id"))
        }

        @Test
        fun `contradictory skip and include with same variable are pruned`() {
            val selections = mk("Foo", "... @skip(if: \$condition) { ... @include(if: \$condition) { id } }")

            assertFalse(selections.containsField("Foo", "id"))
        }

        @Test
        fun `unsatisfiable type condition is pruned`() {
            val selections = mk("Foo", "... on Node { ... on Baz { id } }")

            assertFalse(selections.containsField("Foo", "id"))
        }

        @Test
        fun `cyclic fragment spreads terminate`() {
            val selections = mk(
                "Node",
                """
                    fragment A on Node { ...B }
                    fragment B on Node { ...A }
                    fragment Main on Node { ...A }
                """.trimIndent(),
            )

            assertTrue(selections.isEmpty())
        }

        @Test
        fun `missing fragment definition throws`() {
            val selections = mkWithPrunedFragments(
                "Foo",
                """
                    fragment Main on Foo { ...Fields }
                    fragment Fields on Foo { id }
                """.trimIndent(),
            )

            assertThrows<IllegalArgumentException> {
                selections.selections()
            }
        }

        @Test
        fun `skipped inline fragment tolerates pruned nested fragment definition`() {
            val selections = mkWithPrunedFragments(
                "Foo",
                """
                    fragment Main on Foo {
                      id
                      ... on Foo @skip(if: ${'$'}skipFragment) {
                        ...Fields
                      }
                    }
                    fragment Fields on Foo { int }
                """.trimIndent(),
                vars = mapOf("skipFragment" to true),
            )

            assertTrue(selections.containsField("Foo", "id"))
            assertFalse(selections.containsField("Foo", "int"))
        }

        @Test
        fun `skipped fragment spread tolerates pruned fragment definition`() {
            val selections = mkWithPrunedFragments(
                "Foo",
                """
                    fragment Main on Foo {
                      id
                      ...Fields @skip(if: ${'$'}skipFragment)
                    }
                    fragment Fields on Foo { int }
                """.trimIndent(),
                vars = mapOf("skipFragment" to true),
            )

            assertTrue(selections.containsField("Foo", "id"))
            assertFalse(selections.containsField("Foo", "int"))
        }

        @Test
        fun `active fragment spread requires pruned fragment definition`() {
            val selections = mkWithPrunedFragments(
                "Foo",
                """
                    fragment Main on Foo { ...Fields @skip(if: ${'$'}skipFragment) }
                    fragment Fields on Foo { id }
                """.trimIndent(),
                vars = mapOf("skipFragment" to false),
            )

            assertThrows<IllegalArgumentException> {
                selections.selections()
            }
        }
    }

    @Nested
    inner class Selections {
        @Test
        fun `simple`() {
            val ss = mk("Node", "id")
            assertEquals(
                listOf(EngineSelection("Node", "id", "id")),
                ss.selections()
            )
        }

        @Test
        fun `merged`() {
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
        fun `aliased`() {
            val ss = mk("Node", "alias: id")
            assertEquals(
                listOf(EngineSelection("Node", "id", "alias")),
                ss.selections()
            )
        }

        @Test
        fun `skip and include`() {
            mk("Node", "id @skip(if:true)").let {
                assertEquals(emptyList<EngineSelection>(), it.selections())
            }
            mk("Node", "id @include(if:false)").let {
                assertEquals(emptyList<EngineSelection>(), it.selections())
            }
        }

        @Test
        fun `interface`() {
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
        fun `abstract and concrete fields`() {
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
    }

    @Nested
    inner class TraversableSelections {
        @Test
        fun `excludes simple scalar fields`() {
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
        fun `excludes non-spreadable reprojections`() {
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
        fun `includes spreadable reprojections`() {
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
        fun `includes wrapped composite types`() {
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
        fun `self spreads`() {
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
        fun `narrowing spreads`() {
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
    }

    @Nested
    inner class ArgumentsOfSelection {
        @Test
        fun `empty`() {
            val ss = mk("Query", "x @skip(if:true)", "extend type Query { x: Int }")
            assertEquals(null, ss.argumentsOfSelection("Query", "x"))
        }

        @Test
        fun `no args`() {
            val ss = mk("Query", "x", "extend type Query { x: Int }")
            assertEquals(emptyMap<String, Any?>(), ss.argumentsOfSelection("Query", "x"))
        }

        @Test
        fun `args without defaults`() {
            val ss = mk("Query", "x(y:2)", "extend type Query { x(y:Int):Int }")
            assertEquals(mapOf("y" to 2), ss.argumentsOfSelection("Query", "x"))
        }

        @Test
        fun `args with variable`() {
            val ss = mk("Query", "x(y:\$yvar)", "extend type Query { x(y:Int):Int }", mapOf("yvar" to 2))
            assertEquals(mapOf("y" to 2), ss.argumentsOfSelection("Query", "x"))
        }

        @Test
        fun `args with default value`() {
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
        fun `arg of input object`() {
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
        fun `arg of input object with defaults`() {
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
        fun `arg of list`() {
            mk("Query", "x(y: [[1], [2, 3]])", sdl = "extend type Query { x(y: [[Int]]): Int }").let {
                assertEquals(mapOf("y" to listOf(listOf(1), listOf(2, 3))), it.argumentsOfSelection("Query", "x"))
            }
        }

        @Test
        fun `arg of list with defaults`() {
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
        fun `type conditions`() {
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
        fun `arg of list of object with defaults`() {
            val sdl = """
                extend type Query { x(y:[Input] = [{z: 1, input: null}]): Int }
                input Input { z: Int, input: Input }
            """.trimIndent()

            // no args
            mk("Query", "x", sdl = sdl).let {
                assertEquals(mapOf("y" to listOf(mapOf("z" to 1, "input" to null))), it.argumentsOfSelection("Query", "x"))
            }
        }
    }

    @Nested
    inner class ResolveSelection {
        @Test
        fun `unaliased`() {
            mk("Query", "x", sdl = "extend type Query { x: Int }").let {
                assertEquals(
                    EngineSelection("Query", "x", "x"),
                    it.resolveSelection("Query", "x")
                )
            }
        }

        @Test
        fun `aliased`() {
            mk("Query", "y:x", sdl = "extend type Query { x: Int }").let {
                assertEquals(
                    EngineSelection("Query", "x", "y"),
                    it.resolveSelection("Query", "y")
                )
            }
        }

        @Test
        fun `type conditions`() {
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
    }

    @Nested
    inner class ToSelectionSet {
        @Test
        fun `empty`() {
            mk("Query", "x @skip(if:true)", sdl = "extend type Query { x:Int }").let {
                assertEquals("", AstPrinter.printAst(it.toSelectionSet()))
            }
        }

        @Test
        fun `cull empty selections`() {
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
        fun `unbound variables`() {
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
        fun `fragment spreads`() {
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
                              ... on Query {
                                a: x
                                b: x
                                c: x
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
        fun `culls unreachable subselections with unbound contradictory variables`() {
            val selections = mk(
                "Query",
                """
                    q @skip(if: ${'$'}condition) {
                      retained: x
                      excluded: x @include(if: ${'$'}condition)
                    }
                """.trimIndent(),
                sdl = "extend type Query { x: Int q: Query }",
            )

            assertEquals(
                """
                    {
                      ... on Query {
                        q @skip(if: ${'$'}condition) {
                          ... on Query {
                            retained: x
                          }
                        }
                      }
                    }
                """.trimIndent(),
                AstPrinter.printAst(selections.toSelectionSet()),
            )
        }
    }

    @Nested
    inner class PrintAsFieldSet {
        @Test
        fun `empty`() {
            mk("Query", "x @skip(if:true)", "extend type Query {x:Int}").let { ss ->
                assertEquals("", ss.printAsFieldSet())
            }
        }

        @Test
        fun `bound variables`() {
            mk("Query", "x @skip(if:\$var)", "extend type Query {x:Int}", mapOf("var" to false)).let { ss ->
                assertEquals("...on Query{x @skip(if:\$var)}", ss.printAsFieldSet())
            }
        }

        @Test
        fun `unbound variables`() {
            mk("Query", "x @skip(if:\$var)", "extend type Query {x:Int}").let { ss ->
                assertEquals("...on Query{x @skip(if:\$var)}", ss.printAsFieldSet())
            }
        }

        @Test
        fun `fragment spreads`() {
            val sdl = "extend type Query { x:Int, q:Query }"
            val ss = """
                fragment Main on Query { x
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
                    "...on Query{x q{...on Query{a:x b:x c:x}}}",
                    it.printAsFieldSet()
                )
            }
        }
    }

    @Nested
    inner class ConditionallyExcludedResultKeys {
        @Test
        fun `empty when no fields are skipped`() {
            val ss = mk("Foo", "id int")
            assertTrue(ss.conditionallyExcludedResultKeys().isEmpty())
        }

        @Test
        fun `includes field dropped by static skip directive`() {
            val ss = mk("Foo", "id @skip(if:true) int")
            assertEquals(setOf("id"), ss.conditionallyExcludedResultKeys())
            assertEquals(setOf(EngineSelection("Foo", "int", "int")), ss.selections().toSet())
        }

        @Test
        fun `includes field dropped by static include directive`() {
            val ss = mk("Foo", "id @include(if:false) int")
            assertEquals(setOf("id"), ss.conditionallyExcludedResultKeys())
        }

        @Test
        fun `alias is used as result key`() {
            val ss = mk("Foo", "aliasedId: id @skip(if:true) int")
            assertEquals(setOf("aliasedId"), ss.conditionallyExcludedResultKeys())
        }

        @Test
        fun `unbound variable does not exclude field`() {
            val ss = mk("Foo", "id @skip(if:\$var)")
            assertTrue(ss.conditionallyExcludedResultKeys().isEmpty())
            assertEquals(listOf(EngineSelection("Foo", "id", "id")), ss.selections())
        }

        @Test
        fun `addVariables excludes field when variable drops it`() {
            val ss = mk("Foo", "id @skip(if:\$var)")
            val withVar = ss.addVariables(mapOf("var" to true))
            assertEquals(setOf("id"), withVar.conditionallyExcludedResultKeys())
            assertTrue(withVar.selections().isEmpty())
        }

        @Test
        fun `addVariables does not exclude field when variable keeps it`() {
            val ss = mk("Foo", "id @skip(if:\$var)")
            val withVar = ss.addVariables(mapOf("var" to false))
            assertTrue(withVar.conditionallyExcludedResultKeys().isEmpty())
            assertEquals(listOf(EngineSelection("Foo", "id", "id")), withVar.selections())
        }

        @Test
        fun `addVariables accumulates with pre-existing excluded keys`() {
            val ss = mk("Foo", "id @skip(if:true) int @skip(if:\$var)")
            assertEquals(setOf("id"), ss.conditionallyExcludedResultKeys())
            val withVar = ss.addVariables(mapOf("var" to true))
            assertEquals(setOf("id", "int"), withVar.conditionallyExcludedResultKeys())
        }

        @Test
        fun `inline fragment skip tracks child fields as excluded`() {
            // @skip on an inline fragment drops all child fields; their result keys are tracked
            val ss = mk("Foo", "id ... on Foo @skip(if:true) { int }")
            assertFalse(ss.containsField("Foo", "int"))
            assertTrue(ss.conditionallyExcludedResultKeys().contains("int"))
        }

        @Test
        fun `fragment spread skip tracks child fields as excluded`() {
            val ss = mk(
                "Foo",
                """
                    fragment Main on Foo { id ...FooFrag @skip(if:true) }
                    fragment FooFrag on Foo { int }
                """.trimIndent()
            )
            assertFalse(ss.containsField("Foo", "int"))
            assertTrue(ss.conditionallyExcludedResultKeys().contains("int"))
        }

        @Test
        fun `type-specific exclusion does not leak across projection`() {
            // id @skip under ... on Foo should not appear as excluded when projected to Bar
            val ss = mk(
                "ForkIface",
                "... on ForkFoo { id @skip(if:true) int } ... on ForkBar { id int }",
                sdl = """
                    extend type Query { placeholder: Int }
                    interface ForkIface { id: ID! }
                    type ForkFoo implements ForkIface { id: ID!, int: Int }
                    type ForkBar implements ForkIface { id: ID!, int: Int }
                """.trimIndent()
            )
            val fooProjection = ss.selectionSetForType("ForkFoo")
            assertEquals(setOf("id"), fooProjection.conditionallyExcludedResultKeys())
            val barProjection = ss.selectionSetForType("ForkBar")
            assertTrue(barProjection.conditionallyExcludedResultKeys().isEmpty())
        }

        @Test
        fun `same result key dropped under two type conditions both tracked`() {
            // id @skip under both ... on ForkFoo and ... on ForkBar; both projections must see id as excluded
            val ss = mk(
                "ForkIface",
                "... on ForkFoo { id @skip(if:true) int } ... on ForkBar { id @skip(if:true) int }",
                sdl = """
                extend type Query { placeholder: Int }
                interface ForkIface { id: ID! }
                type ForkFoo implements ForkIface { id: ID!, int: Int }
                type ForkBar implements ForkIface { id: ID!, int: Int }
                """.trimIndent()
            )
            val fooProjection = ss.selectionSetForType("ForkFoo")
            assertEquals(setOf("id"), fooProjection.conditionallyExcludedResultKeys())
            val barProjection = ss.selectionSetForType("ForkBar")
            assertEquals(setOf("id"), barProjection.conditionallyExcludedResultKeys())
        }
    }

    @Nested
    inner class AddVariables {
        @Test
        fun `unused variables preserve selections`() {
            val selections = mk("Foo", "id")

            val withVariables = selections.addVariables(mapOf("unused" to true))

            assertEquals(selections.selections(), withVariables.selections())
        }

        @Test
        fun `recomputes directive pruning`() {
            val selections = mk(
                "Foo",
                """
                    id @skip(if: ${'$'}skipField)
                    int @include(if: ${'$'}keepField)
                """.trimIndent(),
            )

            val withVariables = selections.addVariables(
                mapOf(
                    "skipField" to true,
                    "keepField" to false,
                )
            )

            assertTrue(selections.containsField("Foo", "id"))
            assertTrue(selections.containsField("Foo", "int"))
            assertTrue(withVariables.selections().isEmpty())
            assertEquals(setOf("id", "int"), withVariables.conditionallyExcludedResultKeys())
        }

        @Test
        fun `recomputes directive pruning for inline fragments`() {
            val selections = mk("Foo", "... @skip(if: \$skipFragment) { id }")

            val withVariables = selections.addVariables(mapOf("skipFragment" to true))

            assertTrue(selections.containsField("Foo", "id"))
            assertTrue(withVariables.isEmpty())
            assertEquals(setOf("id"), withVariables.conditionallyExcludedResultKeys())
        }

        @Test
        fun `recomputes directive pruning for fragment spreads`() {
            val selections = mk(
                "Foo",
                """
                    fragment Main on Foo { ...Fields @skip(if: ${'$'}skipFragment) }
                    fragment Fields on Foo { id }
                """.trimIndent(),
            )

            val withVariables = selections.addVariables(mapOf("skipFragment" to true))

            assertTrue(selections.containsField("Foo", "id"))
            assertTrue(withVariables.isEmpty())
            assertEquals(setOf("id"), withVariables.conditionallyExcludedResultKeys())
        }

        @Test
        fun `rejects rebinding`() {
            val selections = mk(
                "Foo",
                "id @skip(if: ${'$'}skipField)",
                vars = mapOf("skipField" to true),
            )

            assertThrows<IllegalArgumentException> {
                selections.addVariables(mapOf("skipField" to false))
            }
        }
    }

    @Nested
    inner class ToNodelikeSelectionSet {
        @Test
        fun `preserves type conditions`() {
            val nodeSelections = mk("Foo", "int")
                .toNodelikeSelectionSet("node", emptyList())
                .selectionSetForField("Query", "node")
                .selectionSetForType("Foo")

            assertTrue(nodeSelections.containsField("Foo", "int"))
            assertFalse(nodeSelections.containsField("Node", "int"))
            assertTrue(
                AstPrinter.printAstCompact(nodeSelections.toSelectionSet())
                    .contains("...on Foo{int}")
            )
        }

        @Test
        fun `preserves conditionally excluded result keys`() {
            val nodeSelections = mk("Foo", "id int @skip(if: true)")
                .toNodelikeSelectionSet("node", emptyList())
                .selectionSetForField("Query", "node")
                .selectionSetForType("Foo")

            assertEquals(setOf("int"), nodeSelections.conditionallyExcludedResultKeys())
        }
    }

    @Nested
    inner class ToFragment {
        @Test
        fun `applies variables`() {
            val selections = mk(
                "Foo",
                "id int @skip(if: \$skipInt)",
                vars = mapOf("skipInt" to true),
            )

            val fragment = selections.toFragment()

            assertEquals(mapOf("skipInt" to true), fragment.variables.asMap())
            assertEquals(
                "fragment Main on Foo {...on Foo{id}}",
                fragment.document,
            )
        }
    }

    @Nested
    inner class ContainsField {
        @Test
        fun `simple`() {
            val ss = mk("Foo", "id")
            assertTrue(ss.containsField("Foo", "id"))
            assertFalse(ss.containsField("Foo", "int"))
        }

        @Test
        fun `union`() {
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
        fun `interface`() {
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
        fun `untyped inline fragment inherits enclosing type condition`() {
            val ss = mk("Node", "... on Foo { ... { int } }")
            assertTrue(ss.containsField("Foo", "int"))
            assertFalse(ss.containsField("Node", "int"))
        }

        @Test
        fun `widening inline fragment inherits enclosing type constraint`() {
            val ss = mk("Node", "... on Baz { ... on Node { id } }")

            assertTrue(ss.containsField("Node", "id"))
            assertTrue(ss.containsField("Baz", "id"))
            assertFalse(ss.containsField("Foo", "id"))
        }

        @Test
        fun `simple type projections do not change contained fields`() {
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
        fun `projecting into sibling types prunes fields`() {
            val ss = mk("Foo", "id bar")
                .selectionSetForType("Node")
                .selectionSetForType("Baz")

            assert(ss.isEmpty())
        }
    }

    @Nested
    inner class ContainsSelection {
        @Test
        fun `empty`() {
            mk("Query", "__typename @skip(if:true)", sdl = "extend type Query { x: Int }").let {
                // valid field but not selected
                assertFalse(it.containsSelection("Query", "x"))

                // unselected alias
                assertFalse(it.containsSelection("Query", "alias"))
            }
        }

        @Test
        fun `fields and aliases`() {
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
        fun `type conditions`() {
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
    }

    @Nested
    inner class RequestsType {
        @Test
        fun `simple object`() {
            val ss = mk("Foo", "int")
            assertTrue(ss.requestsType("Foo"))
        }

        @Test
        fun `empty object`() {
            val ss = mk("Foo", "__typename @skip(if:true)")
            assertTrue(ss.requestsType("Foo"))
            assertTrue(ss.requestsType("Node"))
        }

        @Test
        fun `union narrowing`() {
            val ss = mk("FooOrStruct", "... on Foo { int }")
            // self
            assertTrue(ss.requestsType("Foo"))

            // union narrowing
            assertTrue(ss.requestsType("Foo"))
            assertFalse(ss.requestsType("Struct"))
        }

        @Test
        fun `union widening`() {
            val ss = mk("Foo", "int")

            // union widening
            assertTrue(ss.requestsType("FooUnion"))
            assertTrue(ss.requestsType("FooOrStruct"))

            // union sibling member
            assertFalse(ss.requestsType("Struct"))
        }

        @Test
        fun `interface`() {
            val ss = mk("Node", "... on Foo { int }")
            // self
            assertTrue(ss.requestsType("Node"))

            // interface narrowing
            assertTrue(ss.requestsType("Foo"))
            assertFalse(ss.requestsType("Baz"))
        }

        @Test
        fun `interface widening`() {
            val ss = mk("Foo", "int")
            // self
            assertTrue(ss.requestsType("Foo"))

            // interface widening
            assertTrue(ss.requestsType("Node"))

            // interface sibling impl
            assertFalse(ss.requestsType("Baz"))
        }

        @Test
        fun `simple type projections do not change requested types`() {
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
        fun `deeply nested type condition`() {
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
        fun `empty type projection`() {
            val ss = mk("Node", "__typename @skip(if:true)")
                .selectionSetForType("Foo")

            assertTrue(ss.requestsType("Node"))
            assertFalse(ss.requestsType("Foo"))
        }

        @Test
        fun `subselecting an empty field`() {
            val ss = mk("Foo", "__typename @skip(if:true)")
                .selectionSetForField("Foo", "struct")

            assertFalse(ss.requestsType("Foo"))
            assertFalse(ss.requestsType("Struct"))
        }
    }

    @Nested
    inner class SelectionSetForField {
        @Test
        fun `empty object`() {
            assertTrue(
                mk("Foo", "__typename @skip(if:true)")
                    .selectionSetForField("Foo", "foo")
                    .isEmpty()
            )
        }

        @Test
        fun `simple object`() {
            val ss = mk("Foo", "foo { int }")
                .selectionSetForField("Foo", "foo")

            assertEquals(setOf("int"), ss.typeFields.keys)
        }

        @Test
        fun `merged selections`() {
            val ss = mk("Foo", "s1:foo { int } s2:foo { id }")
                .selectionSetForField("Foo", "foo")

            assertEquals(setOf("int", "id"), ss.typeFields.keys)
        }

        @Test
        fun `throws for non-composite field type`() {
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
        fun `throws for unknown field`() {
            val ss = mk("Foo", "__typename @skip(if:true)")
            assertThrows<IllegalArgumentException> {
                ss.selectionSetForField("Foo", "unknown")
            }
        }

        @Test
        fun `unrelated type with same fieldNames`() {
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
        fun `union member`() {
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
        fun `interface impl`() {
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
        fun `multiple fragments`() {
            val ss = mk(
                "Node",
                """
                fragment Main on Node {
                  ... on Foo {
                    foo { int }
                  }
                  ... on Foo {
                    foo { bar }
                  }
                  ... FooFrag
                }
                fragment FooFrag on Foo { foo { id } }
            """
            ).selectionSetForField("Foo", "foo")

            assertEquals(setOf("id", "int", "bar"), ss.typeFields.keys)
        }

        @Test
        fun `interface widening`() {
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
        fun `interface field merging`() {
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
        fun `abstract-abstract interface spreads`() {
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
        fun `recursive field traversal`() {
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
        fun `deeply nested field`() {
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
        fun `composite introspection fields`() {
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
    }

    @Nested
    inner class SelectionSetForSelection {
        @Test
        fun `empty`() {
            mk("Query", "__typename @skip(if:true)", "extend type Query { x: Query }").let {
                assertThrows<IllegalArgumentException> {
                    it.selectionSetForSelection("Query", "x")
                }
            }
        }

        @Test
        fun `invalid`() {
            mk("Query", "x", "extend type Query { x: Int }").let {
                assertThrows<IllegalArgumentException> {
                    it.selectionSetForSelection("Query", "x")
                }
            }
        }

        @Test
        fun `subselect field`() {
            val sdl = "extend type Query { x: Int, y: Int, q: Query }"
            mk("Query", "q { x }, u:q { y }", sdl)
                .selectionSetForSelection("Query", "q")
                .let {
                    assertTrue(it.containsSelection("Query", "x"))
                    assertFalse(it.containsSelection("Query", "y"))
                }
        }

        @Test
        fun `subselect alias`() {
            val sdl = "extend type Query { x: Int, y: Int, q: Query }"
            mk("Query", "a:q { x }, b:q { y }", sdl)
                .also {
                    it.selectionSetForSelection("Query", "a").let { ss ->
                        assertTrue(ss.containsSelection("Query", "x"))
                        assertFalse(ss.containsSelection("Query", "y"))
                        assertFalse(ss.containsSelection("Query", "a"))
                        assertFalse(ss.containsSelection("Query", "b"))
                    }
                }
                .also {
                    it.selectionSetForSelection("Query", "b").let { ss ->
                        assertFalse(ss.containsSelection("Query", "x"))
                        assertTrue(ss.containsSelection("Query", "y"))
                        assertFalse(ss.containsSelection("Query", "a"))
                        assertFalse(ss.containsSelection("Query", "b"))
                    }
                }
        }

        @Test
        fun `type conditions`() {
            val sdl = """
            extend type Query { empty: Int }
            interface Iface { x: Iface }
            type Foo implements Iface { x: Iface, y: Iface }
            """.trimIndent()

            mk("Iface", "a:x { aa:__typename }, ... on Foo { b:y { bb:__typename } }", sdl).let {
                it.selectionSetForSelection("Foo", "b").let { ss ->
                    assertTrue(ss.containsSelection("Foo", "bb"))
                    assertFalse(ss.containsSelection("Foo", "__typename"))
                }

                it.selectionSetForSelection("Iface", "a").let { ss ->
                    assertTrue(ss.containsSelection("Iface", "aa"))
                    assertFalse(ss.containsSelection("Iface", "__typename"))
                }
            }
            // subselection merging
            mk("Iface", "x {a: __typename}, ... on Foo { x {b: __typename }}", sdl).let {
                // type condition Foo includes same-or-wider sub selections
                it.selectionSetForSelection("Foo", "x").let { ss ->
                    assertTrue(ss.containsSelection("Iface", "a"))
                    assertTrue(ss.containsSelection("Iface", "b"))
                }

                // type condition Iface does not include narrowing sub selectionsl
                it.selectionSetForSelection("Iface", "x").let { ss ->
                    assertTrue(ss.containsSelection("Iface", "a"))
                    assertFalse(ss.containsSelection("Iface", "b"))
                }
            }
        }

        @Test
        fun `composite introspection fields`() {
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
        fun `widening inline fragment keeps enclosing type constraint`() {
            val ss = mk("Foo", "foo { ... on Node { id } }")
                .selectionSetForSelection("Foo", "foo")

            assertTrue(ss.containsField("Node", "id"))
            assertTrue(ss.containsField("Foo", "id"))
            assertFalse(ss.containsField("Baz", "id"))
        }
    }

    @Nested
    inner class SelectionSetForType {
        @Test
        fun `simple object`() {
            val ss = mk("Foo", "int").selectionSetForType("Foo")
            assertEquals(setOf("int"), ss.typeFields.keys)
        }

        @Test
        fun `narrowing union`() {
            val ss = mk(
                "FooOrStruct",
                "__typename ... on Foo { id }"
            )
            assertEquals(ss, ss.selectionSetForType("FooOrStruct"))

            assertEquals(setOf("__typename", "id"), ss.selectionSetForType("Foo").typeFields.keys)
            assertEquals(setOf("__typename"), ss.selectionSetForType("Struct").typeFields.keys)
        }

        @Test
        fun `narrowing interface`() {
            val ss = mk(
                "Node",
                "id ... on Foo { int }"
            )
            assertEquals(ss, ss.selectionSetForType("Node"))

            assertEquals(setOf("id", "int"), ss.selectionSetForType("Foo").typeFields.keys)
            assertEquals(setOf("id"), ss.selectionSetForType("Baz").typeFields.keys)
        }

        @Test
        fun `widening inline fragment keeps enclosing type constraint`() {
            val ss = mk("Node", "... on Baz { ... on Node { id } }")

            assertEquals(setOf("id"), ss.selectionSetForType("Node").typeFields.keys)
            assertEquals(setOf("id"), ss.selectionSetForType("Baz").typeFields.keys)
            assertEquals(emptySet<String>(), ss.selectionSetForType("Foo").typeFields.keys)
        }

        @Test
        fun `widening fragment spread keeps enclosing type constraint`() {
            val ss = mk(
                "Node",
                """
                fragment Main on Node {
                  ... on Baz {
                    ...Frag
                  }
                }
                fragment Frag on Node { id }
                """.trimIndent()
            )

            assertEquals(setOf("id"), ss.selectionSetForType("Node").typeFields.keys)
            assertEquals(setOf("id"), ss.selectionSetForType("Baz").typeFields.keys)
            assertEquals(emptySet<String>(), ss.selectionSetForType("Foo").typeFields.keys)
        }

        @Test
        fun `widen to interface`() {
            val ss = mk(
                "Foo",
                "int ... on Node { id }"
            )
            assertEquals(setOf("id"), ss.selectionSetForType("Node").typeFields.keys)
        }

        @Test
        fun `widen to union`() {
            val ss = mk(
                "Foo",
                "int ... on FooOrStruct { __typename }"
            )
            assertEquals(setOf("__typename"), ss.selectionSetForType("FooOrStruct").typeFields.keys)
        }

        @Test
        fun `abstract-abstract interface spreads`() {
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
        fun `throws for unrelated type`() {
            val ss = mk("Foo", "__typename")
            assertThrows<IllegalArgumentException> {
                ss.selectionSetForType("Baz")
            }
        }

        @Test
        fun `throws for non-composite type`() {
            val ss = mk("Foo", "__typename")
            assertThrows<IllegalArgumentException> {
                ss.selectionSetForType("ID")
            }
        }

        @Test
        fun `deeply nested type condition`() {
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
        fun `identity optimization returns same object for same concrete type`() {
            val ss = mk("Foo", "id") // produces EngineSelectionSetImpl(def=Foo)
            assertSame(ss, ss.selectionSetForType("Foo")) // hits the identity check u == this.def and returns this
        }
    }

    @Nested
    inner class IsEmpty {
        @Test
        fun `follows runtime directives`() {
            val selections = mk(
                "Foo",
                "id @skip(if: ${'$'}skipField)",
                vars = mapOf("skipField" to true),
            )

            assertTrue(selections.isEmpty())
        }

        @Test
        fun `follows runtime directives inside inline fragments`() {
            val selections = mk(
                "Node",
                "... on Node { __typename @skip(if: \$skipField) }",
                vars = mapOf("skipField" to true),
            )

            assertTrue(selections.isEmpty())
        }

        @Test
        fun `follows runtime directives inside fragment definitions`() {
            val selections = mk(
                "Node",
                """
                    fragment Main on Node { ...Fields }
                    fragment Fields on Foo { __typename @skip(if: ${'$'}skipField) }
                """.trimIndent(),
                vars = mapOf("skipField" to true),
            )

            assertTrue(selections.isEmpty())
        }
    }

    @Nested
    inner class IsTransitivelyEmpty {
        @Test
        fun `follows nested runtime directives`() {
            val selections = "foo { id @skip(if: ${'$'}skipField) }"

            assertFalse(mk("Foo", selections, vars = mapOf("skipField" to false)).isTransitivelyEmpty())
            assertTrue(mk("Foo", selections, vars = mapOf("skipField" to true)).isTransitivelyEmpty())
        }

        @Test
        fun `follows runtime directives on nested inline fragments`() {
            val selections = "foo { ... on Foo @skip(if: ${'$'}skipFragment) { __typename } }"

            assertFalse(mk("Foo", selections, vars = mapOf("skipFragment" to false)).isTransitivelyEmpty())
            assertTrue(mk("Foo", selections, vars = mapOf("skipFragment" to true)).isTransitivelyEmpty())
        }

        @Test
        fun `follows runtime directives on nested fragment spreads`() {
            val selections = """
                fragment Main on Foo {
                  foo { ...Fields @skip(if: ${'$'}skipFragment) }
                }
                fragment Fields on Foo { __typename }
            """.trimIndent()

            assertFalse(mk("Foo", selections, vars = mapOf("skipFragment" to false)).isTransitivelyEmpty())
            assertTrue(mk("Foo", selections, vars = mapOf("skipFragment" to true)).isTransitivelyEmpty())
        }

        @Test
        fun `follows runtime directives inside nested fragment definitions`() {
            val selections = """
                fragment Main on Foo { foo { ...Fields } }
                fragment Fields on Foo { __typename @skip(if: ${'$'}skipField) }
            """.trimIndent()

            assertFalse(mk("Foo", selections, vars = mapOf("skipField" to false)).isTransitivelyEmpty())
            assertTrue(mk("Foo", selections, vars = mapOf("skipField" to true)).isTransitivelyEmpty())
        }

        @Test
        fun `follows runtime directives through widening and narrowing fragments`() {
            val selections = """
                foo {
                  ... on Node {
                    ... on Foo { __typename @skip(if: ${'$'}skipField) }
                  }
                }
            """.trimIndent()

            assertFalse(mk("Foo", selections, vars = mapOf("skipField" to false)).isTransitivelyEmpty())
            assertTrue(mk("Foo", selections, vars = mapOf("skipField" to true)).isTransitivelyEmpty())
        }

        @Test
        fun `handles composite introspection fields`() {
            assertFalse(mk("Query", "__type(name: \"Foo\") { __typename }").isTransitivelyEmpty())
            assertTrue(mk("Query", "__type(name: \"Foo\") { __typename @skip(if: true) }").isTransitivelyEmpty())
        }

        @Test
        fun `handles non-null wrapped composite fields`() {
            val selections = mk(
                "Node",
                "... on Container { struct { int @skip(if: true) } }",
                sdl = """
                    type Container implements Node { id: ID!, struct: Struct! }
                    type Struct { int: Int }
                """.trimIndent(),
            )

            assertTrue(selections.isTransitivelyEmpty())
            assertTrue(selections.selectionSetForType("Container").isTransitivelyEmpty())
        }
    }

    @Nested
    inner class FieldDirectivesOfSelection {
        @Test
        fun `detects directive and coerces arguments`() {
            val sdl = """
                extend type Query { x: Int }
                directive @testDirective(flag: Boolean!, label: String) on FIELD
            """.trimIndent()
            val ss = mk(
                "Query",
                "alias: x @testDirective(flag: ${'$'}flag, label: \"ok\")",
                sdl,
                vars = mapOf("flag" to true)
            )

            val directives = ss.fieldDirectivesOfSelection("Query", "alias")!!

            assertTrue(directives.hasDirective("testDirective"))
            assertTrue(
                directives.hasDirective("testDirective") { args ->
                    args["flag"] == true && args["label"] == "ok"
                }
            )
            assertFalse(directives.hasDirective("missingDirective"))
        }
    }

    private fun mk(
        typeName: String,
        selections: String,
        sdl: String = defaultSdl,
        vars: Map<String, Any?> = emptyMap(),
    ): EngineSelectionSet {
        val schema = MockSchema.mk(sdl)
        val docString = AstPrinter.printAst(SelectionsParser.parse(typeName, selections).toDocument())
        return ExecutionSelectionSet.create(
            schema = schema,
            queryPlan = buildPlan(docString, schema),
            variables = vars,
        )
    }

    private fun mkWithPrunedFragments(
        typeName: String,
        selections: String,
        sdl: String = defaultSdl,
        vars: Map<String, Any?> = emptyMap(),
    ): EngineSelectionSet {
        val schema = MockSchema.mk(sdl)
        val docString = AstPrinter.printAst(SelectionsParser.parse(typeName, selections).toDocument())
        val queryPlan = buildPlan(docString, schema)
        return ExecutionSelectionSet.create(
            schema = schema,
            queryPlan = queryPlan.copy(fragments = QueryPlan.Fragments.empty),
            variables = vars,
        )
    }
}
