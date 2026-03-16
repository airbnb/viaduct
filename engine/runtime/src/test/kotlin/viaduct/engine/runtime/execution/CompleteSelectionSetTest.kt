package viaduct.engine.runtime.execution

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.api.CompleteSelectionSetOptions
import viaduct.engine.api.ResolveSelectionSetOptions
import viaduct.engine.api.mocks.MockTenantModuleBootstrapper
import viaduct.engine.api.mocks.createEngineObjectData
import viaduct.engine.api.mocks.createRSS
import viaduct.engine.api.mocks.runFeatureTest
import viaduct.engine.runtime.ObjectEngineResult
import viaduct.engine.runtime.ObjectEngineResultImpl

/**
 * Test suite for the completeSelectionSet API.
 *
 * completeSelectionSet completes already-resolved fields from an ObjectEngineResult
 * into a graphql-java ExecutionResult (data Map + errors). Unlike resolveSelectionSet
 * which triggers field resolution, completeSelectionSet transforms OER values that
 * were already resolved during the normal query plan execution.
 *
 * Tests are organized into:
 * - Object-typed completions (non-Query type) — the primary production use case
 *   (DFP shims, checker execution). Uses objectSelections to populate the parent OER.
 * - Query-typed completions — uses querySelections to populate queryEngineResult.
 * - Error handling and edge cases.
 */
class CompleteSelectionSetTest {
    // ==================== Object-typed completions (non-Query) ====================
    // These exercise the forChildPlan path where isRootQueryQueryPlan=false,
    // which uses parentEngineResult. This is the primary production path for
    // DFP shims (ClassicDerivedFieldResolverExecutor) and checker execution.

    @Test
    fun `object-typed completion with scalar fields`() {
        MockTenantModuleBootstrapper(
            """
            extend type Query {
                container: Container
            }

            type Container {
                x: Int
                y: String
                completedResult: String
            }
            """.trimIndent()
        ) {
            fieldWithValue("Container" to "x", 42)
            fieldWithValue("Container" to "y", "hello")

            field("Query" to "container") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(
                            schema.schema.getObjectType("Container"),
                            mapOf()
                        )
                    }
                }
            }

            field("Container" to "completedResult") {
                resolver {
                    // Targets "Container" (not Query), so isRootQueryQueryPlan = false
                    objectSelections("x y")
                    fn { _, _, _, _, ctx ->
                        val rss = createRSS("Container", "x y")
                        val result = ctx.completeSelectionSet(rss)

                        val data = result.getData<Map<String, Any?>>()
                        "x=${data["x"]}, y=${data["y"]}"
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{ container { completedResult } }")
                .assertJson("""{"data": {"container": {"completedResult": "x=42, y=hello"}}}""")
        }
    }

    @Test
    fun `object-typed completion with explicit targetResult`() {
        MockTenantModuleBootstrapper(
            """
            extend type Query {
                container: Container
            }

            type Container {
                value: Int
                completedResult: Int
            }
            """.trimIndent()
        ) {
            fieldWithValue("Container" to "value", 42)

            field("Query" to "container") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(
                            schema.schema.getObjectType("Container"),
                            mapOf()
                        )
                    }
                }
            }

            field("Container" to "completedResult") {
                resolver {
                    objectSelections("value")
                    fn { _, _, _, _, ctx ->
                        val params = ctx.executionHandle as ExecutionParameters
                        val containerOER = params.parentEngineResult

                        val rss = createRSS("Container", "value")
                        val result = ctx.completeSelectionSet(
                            rss,
                            targetResult = containerOER,
                        )

                        val data = result.getData<Map<String, Any?>>()
                        data["value"]
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{ container { completedResult } }")
                .assertJson("""{"data": {"container": {"completedResult": 42}}}""")
        }
    }

    @Test
    fun `isFieldTypePlan requires targetResult`() {
        MockTenantModuleBootstrapper(
            """
            extend type Query {
                container: Container
            }

            type Container {
                value: Int
                completedResult: Int
            }
            """.trimIndent()
        ) {
            fieldWithValue("Container" to "value", 1)

            field("Query" to "container") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(
                            schema.schema.getObjectType("Container"),
                            mapOf()
                        )
                    }
                }
            }

            field("Container" to "completedResult") {
                resolver {
                    objectSelections("value")
                    fn { _, _, _, _, ctx ->
                        val rss = createRSS("Container", "value")
                        ctx.completeSelectionSet(
                            rss,
                            options = CompleteSelectionSetOptions(isFieldTypePlan = true),
                        )
                        0
                    }
                }
            }
        }.runFeatureTest {
            val result = runQuery("{ container { completedResult } }")

            assertEquals(1, result.errors.size)
            val errorMessage = result.errors.first().message
            assertTrue(
                errorMessage.contains("targetResult is required when isFieldTypePlan is true") ||
                    errorMessage.contains("Failed to build QueryPlan"),
                "Expected error about missing targetResult or QueryPlan build failure, got: $errorMessage"
            )
        }
    }

    // ==================== Query-typed completions ====================
    // These exercise the forChildPlan path where isRootQueryQueryPlan=true,
    // which uses queryEngineResult. Some DFPs have querySelections that follow this path.

    @Test
    fun `query-typed completion via querySelections`() {
        MockTenantModuleBootstrapper(
            """
            extend type Query {
                rootValue: Int
                name: String
                container: Container
            }

            type Container {
                completedResult: String
            }
            """.trimIndent()
        ) {
            fieldWithValue("Query" to "rootValue", 42)
            fieldWithValue("Query" to "name", "Alice")

            field("Query" to "container") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(
                            schema.schema.getObjectType("Container"),
                            mapOf()
                        )
                    }
                }
            }

            field("Container" to "completedResult") {
                resolver {
                    // Targets "Query" type, so isRootQueryQueryPlan = true
                    querySelections("rootValue name")
                    fn { _, _, _, _, ctx ->
                        val requiredSS = createRSS("Query", "rootValue name")
                        val result = ctx.completeSelectionSet(requiredSS)

                        val data = result.getData<Map<String, Any?>>()
                        "rootValue=${data["rootValue"]}, name=${data["name"]}"
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{ container { completedResult } }")
                .assertJson("""{"data": {"container": {"completedResult": "rootValue=42, name=Alice"}}}""")
        }
    }

    @Test
    fun `query-typed completion with explicit targetResult via resolveSelectionSet`() {
        // Explicit targetResult is honored even for Query-typed selections (engine fix).
        MockTenantModuleBootstrapper(
            """
            extend type Query {
                rootValue: Int
                name: String
                container: Container
            }

            type Container {
                completedResult: String
            }
            """.trimIndent()
        ) {
            fieldWithValue("Query" to "rootValue", 42)
            fieldWithValue("Query" to "name", "Alice")

            field("Query" to "container") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(
                            schema.schema.getObjectType("Container"),
                            mapOf()
                        )
                    }
                }
            }

            field("Container" to "completedResult") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        val oer = ObjectEngineResultImpl.newForType(schema.schema.queryType)
                        val rss = ctx.engineSelectionSetFactory
                            .engineSelectionSet("Query", "rootValue name", emptyMap())
                        ctx.resolveSelectionSet(
                            rss,
                            ResolveSelectionSetOptions(targetResult = oer)
                        )

                        val requiredSS = createRSS("Query", "rootValue name")
                        val result = ctx.completeSelectionSet(requiredSS, targetResult = oer)

                        val data = result.getData<Map<String, Any?>>()
                        "rootValue=${data["rootValue"]}, name=${data["name"]}"
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{ container { completedResult } }")
                .assertJson("""{"data": {"container": {"completedResult": "rootValue=42, name=Alice"}}}""")
        }
    }

    // ==================== Error handling ====================

    @Test
    fun `rejects invalid targetResult type`() {
        MockTenantModuleBootstrapper(
            """
            extend type Query {
                rootValue: Int
                container: Container
            }

            type Container {
                completedResult: Int
            }
            """.trimIndent()
        ) {
            fieldWithValue("Query" to "rootValue", 1)

            field("Query" to "container") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(
                            schema.schema.getObjectType("Container"),
                            mapOf()
                        )
                    }
                }
            }

            field("Container" to "completedResult") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        val requiredSS = createRSS("Query", "rootValue")

                        val fakeOER = object : ObjectEngineResult {
                            override val type = schema.schema.queryType

                            override suspend fun fetch(
                                key: ObjectEngineResult.Key,
                                slotNo: Int
                            ): Any? {
                                error("should not be called")
                            }
                        }

                        ctx.completeSelectionSet(requiredSS, targetResult = fakeOER)
                        0
                    }
                }
            }
        }.runFeatureTest {
            val result = runQuery("{ container { completedResult } }")

            assertEquals(1, result.errors.size)
            assertTrue(
                result.errors.first().message.contains("targetResult must be an ObjectEngineResultImpl"),
                "Expected error about invalid targetResult type, got: ${result.errors.first().message}"
            )
        }
    }

    @Test
    fun `rejects incompatible targetResult type for selection set`() {
        MockTenantModuleBootstrapper(
            """
            extend type Query {
                container: Container
            }

            type Container {
                value: Int
                completedResult: Int
            }

            type User {
                id: ID
            }
            """.trimIndent()
        ) {
            fieldWithValue("Container" to "value", 1)

            field("Query" to "container") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(
                            schema.schema.getObjectType("Container"),
                            mapOf()
                        )
                    }
                }
            }

            field("Container" to "completedResult") {
                resolver {
                    objectSelections("value")
                    fn { _, _, _, _, ctx ->
                        // RSS is for Container, but OER is for User — incompatible
                        val userOER = ObjectEngineResultImpl.newForType(
                            schema.schema.getObjectType("User")
                        )
                        val rss = createRSS("Container", "value")
                        ctx.completeSelectionSet(rss, targetResult = userOER)
                        0
                    }
                }
            }
        }.runFeatureTest {
            val result = runQuery("{ container { completedResult } }")

            assertEquals(1, result.errors.size)
            assertTrue(
                result.errors.first().message.contains("not compatible with"),
                "Expected type compatibility error, got: ${result.errors.first().message}"
            )
        }
    }
}
