package viaduct.engine.runtime.execution

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import viaduct.engine.api.mocks.MockTenantModuleBootstrapper
import viaduct.engine.api.mocks.fetchAs
import viaduct.engine.api.mocks.mkEngineObjectData
import viaduct.engine.api.mocks.mkSchemaWithWiring
import viaduct.engine.api.mocks.runFeatureTest

/**
 * Tests that verify subquery execution uses the correct schema.
 *
 * ## Background
 *
 * Viaduct has two schema variants:
 * - **fullSchema**: The complete schema with all fields, used for execution
 * - **scopedSchema**: A potentially subset schema used for introspection queries
 *
 * The `activeSchema` switches between these based on whether the query is introspective.
 * `ScopeInstrumentation` handles this swap.
 *
 * ## The Problem
 *
 * Subqueries executed via `ctx.query()` and `ctx.mutation()` were incorrectly using
 * `activeSchema` for QueryPlan building. This caused subqueries to fail during
 * introspective operations because they were planned against the scoped schema
 * instead of the full schema.
 *
 * ## Expected Behavior
 *
 * Subqueries should ALWAYS execute against `fullSchema`, regardless of whether
 * the outer operation is introspective. This is because:
 * 1. `RawSelectionSetFactoryImpl` builds RawSelectionSets with `fullSchema`
 * 2. `EngineExecutionContextImpl.query/mutation` uses `fullSchema.schema.queryType/mutationType`
 * 3. Subqueries are internal server-side calls that shouldn't be constrained by
 *    the client-visible schema scope
 */
class SubquerySchemaTest {
    /**
     * Tests that subqueries can access fields from fullSchema even when the outer
     * operation is executed with a scopedSchema that doesn't contain those fields.
     *
     * This simulates the scenario where:
     * 1. The scopedSchema is a subset that doesn't include certain fields
     * 2. A resolver executes a subquery requesting a field from fullSchema
     * 3. The subquery should succeed because it uses fullSchema, not scopedSchema
     */
    @Test
    fun `subquery uses fullSchema even when outer query uses scopedSchema`() {
        val fullSchemaSDL = """
            extend type Query {
                publicField: String
                internalField: String
                container: Container
            }
            
            type Container {
                derivedFromInternal: String
            }
        """.trimIndent()

        val scopedSchemaSDL = """
            extend type Query {
                publicField: String
                container: Container
            }
            
            type Container {
                derivedFromInternal: String
            }
        """.trimIndent()

        val fullSchema = mkSchemaWithWiring(fullSchemaSDL)
        val scopedSchema = mkSchemaWithWiring(scopedSchemaSDL)

        MockTenantModuleBootstrapper(fullSchema) {
            fieldWithValue("Query" to "publicField", "public value")
            fieldWithValue("Query" to "internalField", "internal value")

            field("Query" to "container") {
                resolver {
                    fn { _, _, _, _, _ ->
                        mkEngineObjectData(
                            schema.schema.getObjectType("Container"),
                            mapOf()
                        )
                    }
                }
            }

            field("Container" to "derivedFromInternal") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        val rss = ctx.rawSelectionSetFactory
                            .rawSelectionSet("Query", "internalField", emptyMap())

                        val queryResult = ctx.query(
                            resolverId = "Container.derivedFromInternal",
                            selectionSet = rss
                        )

                        val internalValue = queryResult.fetchAs<String>("internalField")
                        "derived: $internalValue"
                    }
                }
            }
        }.runFeatureTest(schema = scopedSchema) {
            runQuery("{ container { derivedFromInternal } }")
                .assertJson("""{"data": {"container": {"derivedFromInternal": "derived: internal value"}}}""")
        }
    }

    /**
     * Tests that ctx.mutation() subqueries use fullSchema even when executing
     * with a scopedSchema that has a different mutation type.
     */
    @Test
    fun `mutation subquery uses fullSchema even when outer query uses scopedSchema`() {
        val fullSchemaSDL = """
            extend type Query {
                container: Container
            }
            
            extend type Mutation {
                publicMutation: Int
                internalMutation: Int
            }
            
            type Container {
                triggerInternalMutation: Int
            }
        """.trimIndent()

        val scopedSchemaSDL = """
            extend type Query {
                container: Container
            }
            
            extend type Mutation {
                publicMutation: Int
            }
            
            type Container {
                triggerInternalMutation: Int
            }
        """.trimIndent()

        val fullSchema = mkSchemaWithWiring(fullSchemaSDL)
        val scopedSchema = mkSchemaWithWiring(scopedSchemaSDL)

        var internalCounter = 0

        MockTenantModuleBootstrapper(fullSchema) {
            field("Mutation" to "publicMutation") {
                resolver {
                    fn { _, _, _, _, _ -> 0 }
                }
            }

            field("Mutation" to "internalMutation") {
                resolver {
                    fn { _, _, _, _, _ ->
                        ++internalCounter
                    }
                }
            }

            field("Query" to "container") {
                resolver {
                    fn { _, _, _, _, _ ->
                        mkEngineObjectData(
                            schema.schema.getObjectType("Container"),
                            mapOf()
                        )
                    }
                }
            }

            field("Container" to "triggerInternalMutation") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        val rss = ctx.rawSelectionSetFactory
                            .rawSelectionSet("Mutation", "internalMutation", emptyMap())

                        val mutationResult = ctx.mutation(
                            resolverId = "Container.triggerInternalMutation",
                            selectionSet = rss
                        )

                        mutationResult.fetchAs<Int>("internalMutation")
                    }
                }
            }
        }.runFeatureTest(schema = scopedSchema) {
            runQuery("{ container { triggerInternalMutation } }")
                .assertJson("""{"data": {"container": {"triggerInternalMutation": 1}}}""")

            assertEquals(1, internalCounter, "Internal mutation should have been called")
        }
    }

    /**
     * Regression test: Ensure non-scoped queries still work correctly.
     * When fullSchema == scopedSchema (the common case), subqueries should work as before.
     */
    @Test
    fun `subquery works normally when fullSchema equals scopedSchema`() {
        MockTenantModuleBootstrapper(
            """
            extend type Query {
                rootValue: Int
                container: Container
            }

            type Container {
                derivedFromQuery: Int
            }
            """.trimIndent()
        ) {
            fieldWithValue("Query" to "rootValue", 42)

            field("Query" to "container") {
                resolver {
                    fn { _, _, _, _, _ ->
                        mkEngineObjectData(
                            schema.schema.getObjectType("Container"),
                            mapOf()
                        )
                    }
                }
            }

            field("Container" to "derivedFromQuery") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        val rss = ctx.rawSelectionSetFactory
                            .rawSelectionSet("Query", "rootValue", emptyMap())

                        val queryResult = ctx.query(
                            resolverId = "Container.derivedFromQuery",
                            selectionSet = rss
                        )

                        queryResult.fetchAs<Int>("rootValue") * 2
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{ container { derivedFromQuery } }")
                .assertJson("""{"data": {"container": {"derivedFromQuery": 84}}}""")
        }
    }
}
