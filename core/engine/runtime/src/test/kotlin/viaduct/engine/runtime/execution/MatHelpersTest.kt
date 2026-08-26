package viaduct.engine.runtime.execution

import graphql.execution.CoercedVariables
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.runtime.mat.KeyTree
import viaduct.engine.runtime.mat.Mat
import viaduct.engine.runtime.mat.build
import viaduct.engine.runtime.result.ObjectEngineResult
import viaduct.errors.TenantResolverException
import viaduct.errors.TenantUsageException

class MatHelpersTest {
    @Nested
    inner class QueryPlan_KeyTree {
        @Test
        fun `empty selection set projects an empty tree`() {
            val parameters = mkExecutionParameters(
                "extend type Query { x:Int }",
                "Query" to "x",
                "{ x }",
            )
            val queryType = parameters.engineExecutionContext.activeSchema.schema.queryType
            val emptyParameters = parameters.copy(
                selectionSet = QueryPlan.SelectionSet.empty(queryType)
            )

            assertEquals(KeyTree.empty, emptyParameters.queryPlan.keyTree(emptyParameters))
        }

        @Test
        fun `projects scalar field`() {
            val parameters = mkExecutionParameters(
                "extend type Query { x:Int }",
                "Query" to "x",
                "{ x }",
            )
            val shape = KeyTree.build(parameters) {
                field("Query", key("x"))
            }

            assertEquals(shape, parameters.queryPlan.keyTree(parameters))
        }

        @Test
        fun `projects aliases and arguments`() {
            val parameters = mkExecutionParameters(
                "extend type Query { x(n:Int!):Int }",
                "Query" to "x",
                "{ alias: x(n: 1) }",
            )
            val shape = KeyTree.build(parameters) {
                field("Query", key("x", alias = "alias", arguments = mapOf("n" to 1)))
            }

            assertEquals(shape, parameters.queryPlan.keyTree(parameters))
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
        fun `field without a selection set projects an empty tree`() {
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
        fun `projects scalar child field`() {
            val parameters = mkExecutionParameters(
                "extend type Query { foo:Foo } type Foo { x:Int }",
                "Query" to "foo",
                "{ foo { x } }",
            )
            val shape = KeyTree.build(parameters) {
                field("Foo", key("x"))
            }

            assertEquals(
                shape,
                parameters.queryPlan.keyTree(
                    parameters,
                    checkNotNull(parameters.field),
                ),
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

        @Test
        fun `output selection set projection does not include required selections`() {
            val parameters = mkExecutionParameters(
                """
                    extend type Query { foo:Foo }
                    type Foo { x:Int, y:Int, z(value:Int!):Int }
                """.trimIndent(),
                "Query" to "foo",
                "{ foo { x y } }",
            ) {
                field("Foo" to "x") {
                    resolver {
                        objectSelections("a: z(value: 2)")
                        fn { _, _, _, _, _ -> null }
                    }
                }
                field("Foo" to "y") {
                    resolver {
                        objectSelections("b: z(value: 3)")
                        fn { _, _, _, _, _ -> null }
                    }
                }
            }

            assertEquals(
                KeyTree.empty,
                parameters.queryPlan.keyTree(
                    parameters,
                    checkNotNull(parameters.field),
                    outputSelectionSetFilter =
                        FieldOutputSelectionSetFilter { _, fieldName ->
                            fieldName == "x" || fieldName == "y"
                        },
                ),
            )
        }

        @Test
        fun `output selection set projection rejects an unresolved variable`() {
            val parameters = mkExecutionParameters(
                "extend type Query { x(value:Int!):Int }",
                "Query" to "x",
                "query (${'$'}value:Int! = 2) { x(value: ${'$'}value) }",
            ).copy(coercedVariables = CoercedVariables.emptyVariables())

            assertThrows<RuntimeException> {
                parameters.queryPlan.keyTree(
                    parameters,
                    outputSelectionSetFilter =
                        FieldOutputSelectionSetFilter { _, _ -> false },
                )
            }
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

        @Test
        fun `failed materialization preserves tenant exception`() {
            val parameters = mkExecutionParameters(
                "extend type Query { x:Int }",
                "Query" to "x",
                "{ x }",
            )
            val failure = TenantUsageException("not found")

            val result = Mat.Null.failedResultFor(KeyTree.empty, parameters, failure)

            assertSame(failure, result.source.exceptionOrNull())
        }

        @Test
        fun `failed materialization preserves wrapped tenant exception`() {
            val parameters = mkExecutionParameters(
                "extend type Query { x:Int }",
                "Query" to "x",
                "{ x }",
            )
            val failure = TenantUsageException("not found")
            val wrappedFailure = TenantResolverException(failure, "StaySpecialOffer")

            val result = Mat.Null.failedResultFor(KeyTree.empty, parameters, wrappedFailure)

            assertSame(failure, result.source.exceptionOrNull())
        }
    }
}
