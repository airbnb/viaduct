package viaduct.engine.runtime.execution

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.arbitrary.graphql.asViaductSchema
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.ResolvedEngineObjectData
import viaduct.engine.runtime.DispatcherRegistry
import viaduct.engine.runtime.NodeEngineObjectDataImpl
import viaduct.engine.runtime.ObjectEngineResult
import viaduct.engine.runtime.mat.KeyTree
import viaduct.engine.runtime.mat.build

class MatHelpersTest {
    @Nested
    inner class QueryPlan_KeyTree {
        @Test
        fun `empty selection set returns an empty shape`() {
            val parameters = mkExecutionParameters(
                "extend type Query { x:Int }",
                "Query" to "x",
                "{ x }",
            ).copy(selectionSet = QueryPlan.SelectionSet.empty)

            assertEquals(KeyTree.empty, parameters.queryPlan.keyTree(parameters))
        }

        @Test
        fun `projects a scalar field`() {
            val parameters = mkExecutionParameters(
                "extend type Query { x:Int }",
                "Query" to "x",
                "{ x }",
            )

            assertEquals(
                KeyTree.build(parameters) {
                    field("Query", key("x"))
                },
                parameters.queryPlan.keyTree(parameters),
            )
        }

        @Test
        fun `projects aliases and arguments`() {
            val parameters = mkExecutionParameters(
                "extend type Query { x(n:Int!):Int }",
                "Query" to "x",
                "{ alias: x(n: 1) }",
            )

            assertEquals(
                KeyTree.build(parameters) {
                    field("Query", key("x", alias = "alias", arguments = mapOf("n" to 1)))
                },
                parameters.queryPlan.keyTree(parameters),
            )
        }

        @Test
        fun `returns an empty shape when the field has no selection set`() {
            val parameters = mkExecutionParameters(
                "extend type Query { scalar:Int }",
                "Query" to "scalar",
                "{ scalar }",
            )

            assertEquals(
                KeyTree.empty,
                parameters.queryPlan.keyTree(
                    parameters,
                    checkNotNull(parameters.field),
                ),
            )
        }

        @Test
        fun `projects a scalar child field`() {
            val parameters = mkExecutionParameters(
                "extend type Query { foo:Foo } type Foo { x:Int }",
                "Query" to "foo",
                "{ foo { x } }",
            )

            assertEquals(
                KeyTree.build(parameters) {
                    field("Foo", key("x"))
                },
                parameters.queryPlan.keyTree(
                    parameters,
                    checkNotNull(parameters.field),
                ),
            )
        }

        @Test
        fun `projects fields for every possible concrete type`() {
            val parameters = mkExecutionParameters(
                """
                    extend type Query { item:Item }
                    interface Item { x:Int }
                    type Foo implements Item { x:Int }
                    type Bar implements Item { x:Int }
                """.trimIndent(),
                "Query" to "item",
                "{ item { x } }",
            )

            assertEquals(
                KeyTree.build(parameters) {
                    field("Foo", key("x"))
                    field("Bar", key("x"))
                },
                parameters.queryPlan.keyTree(
                    parameters,
                    checkNotNull(parameters.field),
                ),
            )
        }

        @Test
        fun `current selection set does not project required selections`() {
            val parameters = mkExecutionParameters(
                "extend type Query { foo:Foo } type Foo { x:Int y:Int }",
                "Query" to "foo",
                "{ foo { y } }",
            ) {
                field("Foo" to "y") {
                    resolver {
                        objectSelections("x")
                        fn { _, _, _, _, _ -> null }
                    }
                }
            }

            assertEquals(
                KeyTree.build(parameters) {
                    field("Query", key("foo")) {
                        field("Foo", key("y"))
                    }
                },
                parameters.queryPlan.keyTree(parameters),
            )
        }

        @Test
        fun `collected field does not project required selections`() {
            val parameters = mkExecutionParameters(
                "extend type Query { foo:Foo } type Foo { x:Int y:Int }",
                "Query" to "foo",
                "{ foo { y } }",
            ) {
                field("Foo" to "y") {
                    resolver {
                        objectSelections("x")
                        fn { _, _, _, _, _ -> null }
                    }
                }
            }

            assertEquals(
                KeyTree.build(parameters) {
                    field("Foo", key("y"))
                },
                parameters.queryPlan.keyTree(
                    parameters,
                    checkNotNull(parameters.field),
                ),
            )
        }
    }

    @Nested
    inner class ResolveField {
        @Test
        fun `resolves definition alias and coerced arguments from execution parameters`() {
            val parameters = mkExecutionParameters(
                "extend type Query { x(n:Int!):Int }",
                "Query" to "x",
                "query (${'$'}n:Int! = 1) { alias: x(n: ${'$'}n) }",
            )
            val field = checkNotNull(parameters.field)
            val resolved = field.resolveField(
                parameters,
                parameters.currentObjectEngineResult.type,
            )

            assertSame(parameters.executionStepInfo.fieldDefinition, resolved.fieldDefinition)
            assertEquals(
                ObjectEngineResult.Key("x", "alias", mapOf("n" to 1)),
                field.oerKey(resolved.arguments),
            )
        }
    }

    @Nested
    inner class FilterByEngineObjectData {
        private val schema = """
            | interface Item { x:Int, y:Int }
            | type Foo {
            |   id:ID!, x(arg:Int):Int, y:Int, bar:Bar, bars:[Bar], barGroups:[[Bar]], items:[Item]
            | }
            | type Bar implements Item { x:Int, y:Int, baz:Baz }
            | type Baz implements Item { x:Int, y:Int, foo:Foo }
        """.trimMargin().asViaductSchema
        private val foo = checkNotNull(schema.schema.getObjectType("Foo"))
        private val bar = checkNotNull(schema.schema.getObjectType("Bar"))
        private val baz = checkNotNull(schema.schema.getObjectType("Baz"))

        @Test
        fun `null source preserves requested coverage`(): Unit =
            runTest {
                val shape = KeyTree.build(schema) {
                    field("Foo", key("x"))
                }

                assertEquals(shape, shape.filterByEngineObjectData(null))
            }

        @Test
        fun `source field names cover matching selections`(): Unit =
            runTest {
                val shape = KeyTree.build(schema) {
                    field("Foo", key("x"))
                }
                val source = ResolvedEngineObjectData(foo, mapOf("x" to 1))

                assertEquals(shape, shape.filterByEngineObjectData(source))
            }

        @Test
        fun `source response keys do not cover aliased selections`(): Unit =
            runTest {
                val shape = KeyTree.build(schema) {
                    field("Foo", key("x", alias = "alias"))
                }
                val source = ResolvedEngineObjectData(foo, mapOf("alias" to 1))

                assertEquals(KeyTree.empty, shape.filterByEngineObjectData(source))
            }

        @Test
        fun `nested source filters nested selections`(): Unit =
            runTest {
                val shape = KeyTree.build(schema) {
                    field("Foo", key("bar")) {
                        field("Bar", key("x"))
                    }
                }
                val source = ResolvedEngineObjectData(
                    foo,
                    mapOf("bar" to ResolvedEngineObjectData(bar, mapOf("x" to 1))),
                )

                assertEquals(shape, shape.filterByEngineObjectData(source))
            }

        @Test
        fun `null nested source preserves requested coverage`(): Unit =
            runTest {
                val shape = KeyTree.build(schema) {
                    field("Foo", key("bar")) {
                        field("Bar", key("x"))
                    }
                }
                val source = ResolvedEngineObjectData(foo, mapOf("bar" to null))

                assertEquals(shape, shape.filterByEngineObjectData(source))
            }

        @Test
        fun `nested field is fetched once for multiple response keys`(): Unit =
            runTest {
                val shape = KeyTree.build(schema) {
                    field("Foo", key("bar", alias = "first")) {
                        field("Bar", key("x"))
                    }
                    field("Foo", key("bar", alias = "second")) {
                        field("Bar", key("x"))
                    }
                }
                val nested = ResolvedEngineObjectData(bar, mapOf("x" to 1))
                var fetchCount = 0
                val source = object : EngineObjectData {
                    override val type = foo

                    override suspend fun fetch(selection: String): Any? = fetchOrNull(selection)

                    override suspend fun fetchOrNull(selection: String): Any? {
                        assertEquals("bar", selection)
                        fetchCount += 1
                        return nested
                    }

                    override suspend fun fetchSelections(): Iterable<String> = setOf("bar")
                }

                assertEquals(shape, shape.filterByEngineObjectData(source))
                assertEquals(1, fetchCount)
            }

        @Test
        fun `truncated source covers only returned parent selection`(): Unit =
            runTest {
                val shape = KeyTree.build(schema) {
                    field("Foo", key("bar")) {
                        field("Bar", key("baz")) {
                            field("Baz", key("x"))
                        }
                    }
                }
                val source = ResolvedEngineObjectData(
                    foo,
                    mapOf("bar" to ResolvedEngineObjectData(bar, emptyMap()))
                )

                assertEquals(
                    KeyTree.build(schema) {
                        field("Foo", key("bar"))
                    },
                    shape.filterByEngineObjectData(source),
                )
            }

        @Test
        fun `list elements contribute only common selections`(): Unit =
            runTest {
                val shape = KeyTree.build(schema) {
                    field("Foo", key("bars")) {
                        field("Bar", key("x"))
                        field("Bar", key("y"))
                    }
                }
                val source = ResolvedEngineObjectData(
                    foo,
                    mapOf(
                        "bars" to listOf(
                            ResolvedEngineObjectData(bar, mapOf("x" to 1)),
                            ResolvedEngineObjectData(bar, mapOf("y" to 2)),
                        )
                    ),
                )

                assertEquals(
                    KeyTree.build(schema) {
                        field("Foo", key("bars"))
                    },
                    shape.filterByEngineObjectData(source),
                )
            }

        @Test
        fun `empty list preserves requested coverage`(): Unit =
            runTest {
                val shape = KeyTree.build(schema) {
                    field("Foo", key("bars")) {
                        field("Bar", key("x"))
                    }
                }
                val source = ResolvedEngineObjectData(
                    foo,
                    mapOf("bars" to emptyList<Any?>()),
                )

                assertEquals(shape, shape.filterByEngineObjectData(source))
            }

        @Test
        fun `list stops inspecting a concrete type after its common selections are empty`(): Unit =
            runTest {
                val shape = KeyTree.build(schema) {
                    field("Foo", key("bars")) {
                        field("Bar", key("x"))
                        field("Bar", key("y"))
                    }
                }
                val uninspected = object : EngineObjectData {
                    override val type = bar

                    override suspend fun fetch(selection: String): Any? = error("exhausted concrete type should not be inspected")

                    override suspend fun fetchOrNull(selection: String): Any? = error("exhausted concrete type should not be inspected")

                    override suspend fun fetchSelections(): Iterable<String> = error("exhausted concrete type should not be inspected")
                }
                val source = ResolvedEngineObjectData(
                    foo,
                    mapOf(
                        "bars" to listOf(
                            ResolvedEngineObjectData(bar, mapOf("x" to 1)),
                            ResolvedEngineObjectData(bar, mapOf("y" to 2)),
                            uninspected,
                        )
                    ),
                )

                assertEquals(
                    KeyTree.build(schema) {
                        field("Foo", key("bars"))
                    },
                    shape.filterByEngineObjectData(source),
                )
            }

        @Test
        fun `list elements retain selections returned by every element`(): Unit =
            runTest {
                val shape = KeyTree.build(schema) {
                    field("Foo", key("bars")) {
                        field("Bar", key("x"))
                        field("Bar", key("y"))
                    }
                }
                val source = ResolvedEngineObjectData(
                    foo,
                    mapOf(
                        "bars" to listOf(
                            ResolvedEngineObjectData(bar, mapOf("x" to 1, "y" to 2)),
                            ResolvedEngineObjectData(bar, mapOf("x" to 3)),
                        )
                    ),
                )

                assertEquals(
                    KeyTree.build(schema) {
                        field("Foo", key("bars")) {
                            field("Bar", key("x"))
                        }
                    },
                    shape.filterByEngineObjectData(source),
                )
            }

        @Test
        fun `null list elements do not remove common selections`(): Unit =
            runTest {
                val shape = KeyTree.build(schema) {
                    field("Foo", key("bars")) {
                        field("Bar", key("x"))
                    }
                }
                val source = ResolvedEngineObjectData(
                    foo,
                    mapOf(
                        "bars" to listOf(
                            ResolvedEngineObjectData(bar, mapOf("x" to 1)),
                            null,
                            ResolvedEngineObjectData(bar, mapOf("x" to 2)),
                        )
                    ),
                )

                assertEquals(shape, shape.filterByEngineObjectData(source))
            }

        @Test
        fun `different concrete list element types contribute independent selections`(): Unit =
            runTest {
                val shape = KeyTree.build(schema) {
                    field("Foo", key("items")) {
                        field("Bar", key("x"))
                        field("Baz", key("y"))
                    }
                }
                val source = ResolvedEngineObjectData(
                    foo,
                    mapOf(
                        "items" to listOf(
                            ResolvedEngineObjectData(bar, mapOf("x" to 1)),
                            ResolvedEngineObjectData(baz, mapOf("y" to 2)),
                        )
                    ),
                )

                assertEquals(shape, shape.filterByEngineObjectData(source))
            }

        @Test
        fun `list preserves requested coverage for absent concrete types`(): Unit =
            runTest {
                val shape = KeyTree.build(schema) {
                    field("Foo", key("items")) {
                        field("Bar", key("x"))
                        field("Baz", key("y"))
                    }
                }
                val source = ResolvedEngineObjectData(
                    foo,
                    mapOf("items" to listOf(ResolvedEngineObjectData(bar, emptyMap()))),
                )

                assertEquals(
                    KeyTree.build(schema) {
                        field("Foo", key("items")) {
                            field("Baz", key("y"))
                        }
                    },
                    shape.filterByEngineObjectData(source),
                )
            }

        @Test
        fun `nested lists intersect selections across every dimension`(): Unit =
            runTest {
                val shape = KeyTree.build(schema) {
                    field("Foo", key("barGroups")) {
                        field("Bar", key("x"))
                    }
                }
                val source = ResolvedEngineObjectData(
                    foo,
                    mapOf(
                        "barGroups" to listOf(
                            listOf(ResolvedEngineObjectData(bar, mapOf("x" to 1))),
                            listOf(ResolvedEngineObjectData(bar, emptyMap())),
                        )
                    ),
                )

                assertEquals(
                    KeyTree.build(schema) {
                        field("Foo", key("barGroups"))
                    },
                    shape.filterByEngineObjectData(source),
                )
            }

        @Test
        fun `node references cover only id`(): Unit =
            runTest {
                val shape = KeyTree.build(schema) {
                    field("Foo", key("id"))
                    field("Foo", key("x"))
                }
                val source = NodeEngineObjectDataImpl(
                    "Foo:1",
                    foo,
                    DispatcherRegistry.Empty,
                )

                assertEquals(
                    KeyTree.build(schema) {
                        field("Foo", key("id"))
                    },
                    shape.filterByEngineObjectData(source),
                )
            }
    }

    @Nested
    inner class FieldOutputSelectionSetFiltering {
        private val type = graphql.schema.GraphQLObjectType.newObject()
            .name("Foo")
            .build()

        @Test
        fun `field filter drops introspection and resolver-owned fields`() {
            val filter = FieldOutputSelectionSetFilter { _, fieldName -> fieldName == "resolved" }

            assertFalse(filter(type, ObjectEngineResult.Key("__typename"), topLevel = true))
            assertFalse(filter(type, ObjectEngineResult.Key("resolved"), topLevel = true))
            assertTrue(filter(type, ObjectEngineResult.Key("plain"), topLevel = true))
        }
    }

    @Nested
    inner class NodeOutputSelectionSetFiltering {
        private val type = graphql.schema.GraphQLObjectType.newObject()
            .name("Foo")
            .build()

        @Test
        fun `node filter drops introspection resolver-owned fields and top-level id`() {
            val filter = NodeOutputSelectionSetFilter { _, fieldName -> fieldName == "resolved" }

            assertFalse(filter(type, ObjectEngineResult.Key("__typename"), topLevel = true))
            assertFalse(filter(type, ObjectEngineResult.Key("id"), topLevel = true))
            assertFalse(filter(type, ObjectEngineResult.Key("resolved"), topLevel = true))
            assertTrue(filter(type, ObjectEngineResult.Key("id"), topLevel = false))
            assertTrue(filter(type, ObjectEngineResult.Key("plain"), topLevel = true))
        }

        @Test
        fun `initial node filter preserves resolver-owned fields`() {
            assertFalse(nodeInitialResolutionFilter(type, ObjectEngineResult.Key("__typename"), topLevel = true))
            assertFalse(nodeInitialResolutionFilter(type, ObjectEngineResult.Key("id"), topLevel = true))
            assertTrue(nodeInitialResolutionFilter(type, ObjectEngineResult.Key("id"), topLevel = false))
            assertTrue(nodeInitialResolutionFilter(type, ObjectEngineResult.Key("plain"), topLevel = true))
        }
    }

    @Nested
    inner class RequireMaterializedNotNull {
        @Test
        fun `returns non-null value`() {
            assertSame(Unit, requireMaterializedNotNull(Unit) { "unused" })
        }

        @Test
        fun `throws materialization exception for null`() {
            val thrown = assertThrows<InternalEngineException> {
                requireMaterializedNotNull(null) { "missing value" }
            }

            assertEquals("missing value", thrown.message)
        }
    }

    @Nested
    inner class MaterializationException {
        @Test
        fun `wraps message in internal engine exception`() {
            val thrown = materializationException("missing value")

            assertEquals("missing value", thrown.message)
            assertEquals(IllegalStateException::class, thrown.cause!!::class)
        }

        @Test
        fun `returns existing internal engine exception`() {
            val existing = materializationException("already wrapped")

            assertSame(existing, materializationException("ignored", cause = existing))
        }
    }
}
