package viaduct.engine.runtime.execution

import graphql.execution.instrumentation.InstrumentationContext
import graphql.execution.instrumentation.InstrumentationState
import graphql.execution.instrumentation.SimpleInstrumentationContext
import graphql.execution.instrumentation.parameters.InstrumentationExecutionStrategyParameters
import graphql.schema.GraphQLObjectType
import io.kotest.matchers.collections.shouldContain
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.EngineConfiguration
import viaduct.engine.api.CompleteSelectionSetOptions
import viaduct.engine.api.instrumentation.IViaductInstrumentation
import viaduct.engine.api.instrumentation.ViaductInstrumentationBase
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.createEngineObjectData
import viaduct.engine.api.mocks.createRSS
import viaduct.engine.api.mocks.featureTestDefault
import viaduct.engine.api.mocks.runFeatureTest
import viaduct.engine.runtime.CheckerSyncEngineObjectData
import viaduct.engine.runtime.EngineExecutionContextExtensions.asImpl
import viaduct.engine.runtime.ObjectEngineResultImpl
import viaduct.engine.runtime.context.getLocalContextForType
import viaduct.engine.runtime.observability.ExecutionObservabilityContext
import viaduct.engine.runtime.result.ObjectEngineResult

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
    // These exercise child-plan parameter creation where isRootQueryQueryPlan=false,
    // which uses currentObjectEngineResult. This is the primary production path for
    // DFP shims (ClassicDerivedFieldResolverExecutor) and checker execution.

    @Test
    fun `object-typed completion with explicit targetResult`() {
        EngineTestModule(
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
                        val containerOER = params.currentObjectEngineResult

                        val rss = createRSS("Container", "value")
                        val checkerData = CheckerSyncEngineObjectData.resolve(
                            objectEngineResult = containerOER,
                            errorMessage = "missing from checker RSS",
                            selectionSet = null,
                        )
                        val result = checkerData.completeSelectionSet(
                            context = ctx,
                            selectionSet = rss,
                            arguments = emptyMap(),
                            options = CompleteSelectionSetOptions.DEFAULT,
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
        EngineTestModule(
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
    // These exercise child-plan parameter creation where isRootQueryQueryPlan=true,
    // which uses queryEngineResult. Some DFPs have querySelections that follow this path.

    @Test
    fun `query-typed completion distinguishes client and child-plan instrumentation`() {
        val completions = CopyOnWriteArrayList<Triple<List<Any>, String, Boolean>>()
        val instrumentation = object : ViaductInstrumentationBase(), IViaductInstrumentation.WithBeginCompleteObject {
            override fun beginCompleteObject(
                parameters: InstrumentationExecutionStrategyParameters,
                state: InstrumentationState?
            ): InstrumentationContext<Any> {
                val step = parameters.executionStrategyParameters.executionStepInfo
                val type = step.unwrappedNonNullType as GraphQLObjectType
                val isChildPlan = parameters.executionContext.getLocalContextForType<ExecutionObservabilityContext>()?.isChildPlan == true
                completions.add(Triple(step.path.toList(), type.name, isChildPlan))
                return SimpleInstrumentationContext.noOp()
            }
        }

        EngineTestModule(
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
        }.runFeatureTest(
            engineConfig = EngineConfiguration.featureTestDefault.copy(
                additionalInstrumentation = instrumentation.asStandardInstrumentation,
                chainInstrumentationWithDefaults = true,
            )
        ) {
            runQuery("{ container { completedResult } }")
                .assertJson("""{"data": {"container": {"completedResult": "rootValue=42, name=Alice"}}}""")

            completions shouldContain Triple(emptyList<Any>(), "Query", false)
            completions shouldContain Triple(emptyList<Any>(), "Query", true)
        }
    }

    // ==================== Error handling ====================

    @Test
    fun `rejects invalid targetResult type`() {
        EngineTestModule(
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

                        ctx.asImpl().completeSelectionSet(requiredSS, targetResult = fakeOER)
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
        EngineTestModule(
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
                        ctx.asImpl().completeSelectionSet(rss, targetResult = userOER)
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
