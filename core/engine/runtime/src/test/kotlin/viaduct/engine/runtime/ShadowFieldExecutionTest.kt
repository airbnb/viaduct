package viaduct.engine.runtime

import graphql.GraphqlErrorBuilder
import graphql.execution.DataFetcherResult
import graphql.execution.instrumentation.InstrumentationState
import graphql.execution.instrumentation.parameters.InstrumentationFieldParameters
import graphql.schema.DataFetcher
import graphql.schema.FieldCoordinates
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.EngineConfiguration
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.ParentManagedValue
import viaduct.engine.api.StandardResolutionValue
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.instrumentation.IViaductInstrumentation
import viaduct.engine.api.instrumentation.ViaductInstrumentationBase
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.MockFieldUnbatchedResolverExecutor
import viaduct.engine.api.mocks.createEngineObjectData
import viaduct.engine.api.mocks.createRSS
import viaduct.engine.api.mocks.createSchemaWithWiring
import viaduct.engine.api.mocks.featureTestDefault
import viaduct.engine.api.mocks.fetchAs
import viaduct.engine.api.mocks.runFeatureTest
import viaduct.engine.api.spi.ShadowFieldExecutionComparison
import viaduct.engine.api.spi.ShadowFieldExecutionResults

@OptIn(ExperimentalCoroutinesApi::class)
class ShadowFieldExecutionTest {
    @Test
    fun `shadow execution is rejected for mutation root fields`() {
        val resolverCalls = AtomicInteger()
        val comparisonCalls = AtomicInteger()
        val instrumentation =
            ShadowFieldExecutionComparisonInstrumentation(
                coordinate = "Mutation" to "update",
                observer = { comparisonCalls.incrementAndGet() },
            )

        EngineTestModule(
            """
            extend type Query { unused: String }
            extend type Mutation { update: String! }
            """.trimIndent(),
        ) {
            field("Mutation" to "update") {
                resolver {
                    fn { _, _, _, _, _ ->
                        resolverCalls.incrementAndGet()
                        "updated"
                    }
                }
            }
        }.runFeatureTest(
            engineConfig =
                EngineConfiguration.featureTestDefault.copy(
                    additionalInstrumentation = instrumentation.asStandardInstrumentation,
                )
        ) {
            runQuery("mutation { update }")
                .assertJson("""{data: {update: "updated"}}""")
        }

        assertEquals(1, resolverCalls.get())
        assertEquals(0, comparisonCalls.get())
    }

    @Test
    fun `shadow execution is rejected for subscription root fields`() {
        val resolverCalls = AtomicInteger()
        val comparisonCalls = AtomicInteger()
        val instrumentation =
            ShadowFieldExecutionComparisonInstrumentation(
                coordinate = "Subscription" to "event",
                observer = { comparisonCalls.incrementAndGet() },
            )

        EngineTestModule(
            """
            extend type Query { unused: String }
            extend type Subscription { event: String! }
            """.trimIndent(),
        ) {
            field("Subscription" to "event") {
                resolver {
                    fn { _, _, _, _, _ ->
                        resolverCalls.incrementAndGet()
                        "published"
                    }
                }
            }
        }.runFeatureTest(
            engineConfig =
                EngineConfiguration.featureTestDefault.copy(
                    additionalInstrumentation = instrumentation.asStandardInstrumentation,
                )
        ) {
            runQuery("subscription { event }")
                .assertJson("""{data: {event: "published"}}""")
        }

        assertEquals(1, resolverCalls.get())
        assertEquals(0, comparisonCalls.get())
    }

    @Test
    fun `shadow execution is rejected for mutation namespace fields`() {
        val resolverCalls = AtomicInteger()
        val comparisonCalls = AtomicInteger()
        val instrumentation =
            ShadowFieldExecutionComparisonInstrumentation(
                coordinate = "MutationNamespace" to "update",
                observer = { comparisonCalls.incrementAndGet() },
            )

        EngineTestModule(
            """
            extend type Query { unused: String }
            extend type Mutation { namespace: MutationNamespace }
            type MutationNamespace @namespaceType { update: String! }
            """.trimIndent(),
        ) {
            field("Mutation" to "namespace") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(
                            schema.schema.getObjectType("MutationNamespace"),
                            emptyMap(),
                        )
                    }
                }
            }
            field("MutationNamespace" to "update") {
                resolver {
                    fn { _, _, _, _, _ ->
                        resolverCalls.incrementAndGet()
                        "updated"
                    }
                }
            }
        }.runFeatureTest(
            engineConfig =
                EngineConfiguration.featureTestDefault.copy(
                    additionalInstrumentation = instrumentation.asStandardInstrumentation,
                )
        ) {
            runQuery("mutation { namespace { update } }")
                .assertJson("""{data: {namespace: {update: "updated"}}}""")
        }

        assertEquals(1, resolverCalls.get())
        assertEquals(0, comparisonCalls.get())
    }

    @Test
    fun `shadow execution remains enabled for ordinary fields reached from a mutation`() {
        val resolverCalls = AtomicInteger()
        val comparisonCalls = AtomicInteger()
        val comparisonCompleted = CompletableDeferred<Unit>()
        val instrumentation =
            ShadowFieldExecutionComparisonInstrumentation(
                coordinate = "MutationResult" to "value",
                observer = {
                    comparisonCalls.incrementAndGet()
                    comparisonCompleted.complete(Unit)
                },
            )

        EngineTestModule(
            """
            extend type Query { unused: String }
            extend type Mutation {
                update: MutationResult!
                comparisonCompleted: Boolean!
            }
            type MutationResult { value: String! }
            """.trimIndent(),
        ) {
            field("Mutation" to "comparisonCompleted") {
                resolver {
                    fn { _, _, _, _, _ ->
                        comparisonCompleted.await()
                        true
                    }
                }
            }
            field("Mutation" to "update") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(
                            schema.schema.getObjectType("MutationResult"),
                            emptyMap(),
                        )
                    }
                }
            }
            field("MutationResult" to "value") {
                resolver {
                    fn { _, _, _, _, _ ->
                        resolverCalls.incrementAndGet()
                        "updated"
                    }
                }
            }
        }.runFeatureTest(
            engineConfig =
                EngineConfiguration.featureTestDefault.copy(
                    additionalInstrumentation = instrumentation.asStandardInstrumentation,
                )
        ) {
            runQuery("mutation { update { value } comparisonCompleted }")
                .assertJson(
                    """{data: {update: {value: "updated"}, comparisonCompleted: true}}"""
                )
        }

        assertEquals(2, resolverCalls.get())
        assertEquals(1, comparisonCalls.get())
    }

    @Test
    fun `shadow comparison preserves data and GraphQL errors returned by resolver`() {
        val results = AtomicReference<ShadowFieldExecutionResults>()
        val comparisonCompleted = CompletableDeferred<Unit>()
        val error = GraphqlErrorBuilder.newError().message("resolver warning").build()
        val instrumentation =
            ShadowFieldExecutionComparisonInstrumentation(
                coordinate = "Query" to "compared",
                observer = {
                    results.set(it)
                    comparisonCompleted.complete(Unit)
                },
            )

        EngineTestModule(
            """
            extend type Query {
                compared: String
                comparisonCompleted: Boolean!
            }
            """.trimIndent()
        ) {
            field("Query" to "comparisonCompleted") {
                resolver {
                    fn { _, _, _, _, _ ->
                        comparisonCompleted.await()
                        true
                    }
                }
            }
            field("Query" to "compared") {
                value(
                    DataFetcherResult.newResult<String>()
                        .data("resolved value")
                        .error(error)
                        .build()
                )
            }
        }.runFeatureTest(
            engineConfig =
                EngineConfiguration.featureTestDefault.copy(
                    additionalInstrumentation = instrumentation.asStandardInstrumentation,
                )
        ) {
            runQuery("{ compared comparisonCompleted }")
        }

        assertEquals("resolved value", results.get().production.rawValue.getOrThrow())
        assertEquals("resolved value", results.get().shadow.rawValue.getOrThrow())
        assertEquals(listOf("resolver warning"), results.get().production.graphqlErrors.map { it.message })
        assertEquals(listOf("resolver warning"), results.get().shadow.graphqlErrors.map { it.message })
    }

    @Test
    fun `shadow execution preserves parent managed routing`() {
        val results = AtomicReference<ShadowFieldExecutionResults>()
        val comparisonCompleted = CompletableDeferred<Unit>()
        val checkerInputs = ConcurrentLinkedQueue<String>()
        val registeredResolverCalls = AtomicInteger()
        val nestedResolverCalls = AtomicInteger()
        val schema = schemaWithClassicFetcher()
        val instrumentation =
            ShadowFieldExecutionComparisonInstrumentation(
                coordinate = "Holder" to "compared",
                observer = {
                    results.set(it)
                    comparisonCompleted.complete(Unit)
                },
            )

        EngineTestModule(schema) {
            field("Query" to "comparisonCompleted") {
                resolver {
                    fn { _, _, _, _, _ ->
                        comparisonCompleted.await()
                        true
                    }
                }
            }
            field("Query" to "holder") {
                resolver {
                    fn { _, _, _, _, _ ->
                        ParentManagedValue(
                            mapOf(
                                "resolverInput" to "resolver-rss",
                                "checkerInput" to "checker-rss",
                            )
                        )
                    }
                }
            }
            field("Holder" to "compared") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        objectSelectionSet = createRSS(
                            typeName = "Holder",
                            selectionString = "resolverInput",
                            executionCondition = QueryPlanExecutionCondition { false },
                        ),
                        resolverId = resolverId,
                    ) { _, objectValue, _, _, _ ->
                        registeredResolverCalls.incrementAndGet()
                        val input = objectValue.get("resolverInput")
                        StandardResolutionValue(mapOf("value" to "shim:$input"))
                    }
                }
                checker {
                    objectSelections("checkerData", "checkerInput")
                    fn { _, objectData ->
                        checkerInputs += objectData.getValue("checkerData").get("checkerInput") as String
                    }
                }
            }
            field("ComparedResult" to "value") {
                resolver {
                    fn { _, _, _, _, _ ->
                        nestedResolverCalls.incrementAndGet()
                        "unexpected-shadow-child"
                    }
                }
            }
        }.runFeatureTest(
            engineConfig =
                EngineConfiguration.featureTestDefault.copy(
                    additionalInstrumentation = instrumentation.asStandardInstrumentation,
                )
        ) {
            runQuery("{ holder { compared { value } } comparisonCompleted }")
                .assertJson(
                    """{data: {holder: {compared: {value: "classic-value"}}, comparisonCompleted: true}}"""
                )
        }

        assertEquals(
            mapOf("value" to "classic-value"),
            results.get().production.rawValue.getOrThrow(),
        )
        assertEquals(
            mapOf("value" to "classic-value"),
            results.get().shadow.rawValue.getOrThrow(),
        )
        assertEquals(0, registeredResolverCalls.get())
        assertEquals(listOf("checker-rss", "checker-rss"), checkerInputs.sorted())
        assertEquals(0, nestedResolverCalls.get())
    }

    @Test
    fun `shadow execution rejects parent fields`() {
        val results = AtomicReference<ShadowFieldExecutionResults>()
        val comparisonCompleted = CompletableDeferred<Unit>()
        val successfulResolverCalls = AtomicInteger()
        val instrumentation =
            ShadowFieldExecutionComparisonInstrumentation(
                coordinate = "User" to "companyName",
                observer = {
                    results.set(it)
                    comparisonCompleted.complete(Unit)
                },
            )

        EngineTestModule(
            """
            extend type Query { company: Company!, comparisonCompleted: Boolean! }
            type Company { name: String!, user: User! }
            type User { parent: Company @parent, companyName: String! }
            """.trimIndent(),
        ) {
            field("Query" to "comparisonCompleted") {
                resolver {
                    fn { _, _, _, _, _ ->
                        comparisonCompleted.await()
                        true
                    }
                }
            }
            field("Query" to "company") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(
                            schema.schema.getObjectType("Company"),
                            mapOf("name" to "Airbnb"),
                        )
                    }
                }
            }
            field("Company" to "user") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(
                            schema.schema.getObjectType("User"),
                            emptyMap(),
                        )
                    }
                }
            }
            field("User" to "companyName") {
                resolver {
                    objectSelections("parent { name }")
                    fn { _, objectValue, _, _, _ ->
                        val companyName = objectValue
                            .fetchAs<EngineObjectData>("parent")
                            .fetchAs<String>("name")
                        successfulResolverCalls.incrementAndGet()
                        companyName
                    }
                }
            }
        }.runFeatureTest(
            engineConfig =
                EngineConfiguration.featureTestDefault.copy(
                    additionalInstrumentation = instrumentation.asStandardInstrumentation,
                )
        ) {
            runQuery("{ company { user { companyName } } comparisonCompleted }")
                .assertJson(
                    """{data: {company: {user: {companyName: "Airbnb"}}, comparisonCompleted: true}}"""
                )
        }

        assertEquals("Airbnb", results.get().production.rawValue.getOrThrow())
        val shadowFailure = checkNotNull(results.get().shadow.rawValue.exceptionOrNull())
        assertTrue(
            generateSequence(shadowFailure) { it.cause }.any {
                it is IllegalStateException &&
                    it.message == "@parent fields are not supported during shadow field execution"
            },
            "Expected an explicit unsupported @parent failure, got $shadowFailure",
        )
        assertEquals(1, successfulResolverCalls.get())
    }

    @Test
    fun `shadow execution request isolates ordinary exceptions`() {
        val instrumentation =
            ThrowingShadowFieldExecutionRequestInstrumentation(
                IllegalStateException("request failed")
            )

        EngineTestModule("extend type Query { value: String! }") {
            field("Query" to "value") {
                resolver {
                    fn { _, _, _, _, _ -> "production" }
                }
            }
        }.runFeatureTest(
            engineConfig =
                EngineConfiguration.featureTestDefault.copy(
                    additionalInstrumentation = instrumentation.asStandardInstrumentation,
                )
        ) {
            runQuery("{ value }")
                .assertJson("""{data: {value: "production"}}""")
        }
    }

    @Test
    fun `shadow execution request propagates errors`() {
        val fatalError = AssertionError("fatal request failure")
        val instrumentation =
            ThrowingShadowFieldExecutionRequestInstrumentation(fatalError)

        EngineTestModule("extend type Query { value: String! }") {
            field("Query" to "value") {
                resolver {
                    fn { _, _, _, _, _ -> "production" }
                }
            }
        }.runFeatureTest(
            engineConfig =
                EngineConfiguration.featureTestDefault.copy(
                    additionalInstrumentation = instrumentation.asStandardInstrumentation,
                )
        ) {
            val thrown = assertThrows<AssertionError> { runQuery("{ value }") }

            assertEquals(fatalError.message, thrown.message)
        }
    }

    @Test
    fun `request supervisor isolates fatal shadow comparison failures`() {
        val fatalError = AssertionError("fatal comparison failure")
        val comparisonCalls = AtomicInteger()
        val comparisonCompleted = CompletableDeferred<Unit>()
        val instrumentation =
            ShadowFieldExecutionComparisonInstrumentation(
                coordinate = "Query" to "value",
                observer = {
                    comparisonCalls.incrementAndGet()
                    comparisonCompleted.complete(Unit)
                    throw fatalError
                },
            )

        EngineTestModule(
            """
            extend type Query {
                value: String!
                comparisonCompleted: Boolean!
            }
            """.trimIndent()
        ) {
            field("Query" to "comparisonCompleted") {
                resolver {
                    fn { _, _, _, _, _ ->
                        comparisonCompleted.await()
                        true
                    }
                }
            }
            field("Query" to "value") {
                resolver {
                    fn { _, _, _, _, _ -> "production" }
                }
            }
        }.runFeatureTest(
            engineConfig =
                EngineConfiguration.featureTestDefault.copy(
                    additionalInstrumentation = instrumentation.asStandardInstrumentation,
                )
        ) {
            runQuery("{ value comparisonCompleted }")
                .assertJson(
                    """{data: {value: "production", comparisonCompleted: true}}"""
                )
        }

        assertEquals(1, comparisonCalls.get())
    }

    private fun schemaWithClassicFetcher(): ViaductSchema {
        val baseSchema = createSchemaWithWiring(
            """
            extend type Query {
                holder: Holder!
                comparisonCompleted: Boolean!
            }

            type Holder {
                compared: ComparedResult!
                resolverInput: String!
                checkerInput: String!
            }

            type ComparedResult {
                value: String!
            }
            """.trimIndent()
        )
        val classicDataFetcher =
            DataFetcher<Any?> {
                mapOf("value" to "classic-value")
            }
        val codeRegistry = baseSchema.schema.codeRegistry.transform { builder ->
            builder.dataFetcher(
                FieldCoordinates.coordinates("Holder", "compared"),
                classicDataFetcher,
            )
        }

        return ViaductSchema(
            baseSchema.schema.transform { builder ->
                builder.codeRegistry(codeRegistry)
            }
        )
    }

    private class ShadowFieldExecutionComparisonInstrumentation(
        private val coordinate: Pair<String, String>,
        private val observer: (ShadowFieldExecutionResults) -> Unit,
    ) : ViaductInstrumentationBase(),
        IViaductInstrumentation.WithShadowFieldExecution {
        override fun requestShadowFieldExecution(
            parameters: InstrumentationFieldParameters,
            state: InstrumentationState?,
        ): ShadowFieldExecutionComparison? {
            val executionStepInfo = parameters.executionStepInfo
            if (executionStepInfo.objectType.name to executionStepInfo.field.name != coordinate) {
                return null
            }

            return ShadowFieldExecutionComparison(observer)
        }
    }

    private class ThrowingShadowFieldExecutionRequestInstrumentation(
        private val throwable: Throwable,
    ) : ViaductInstrumentationBase(),
        IViaductInstrumentation.WithShadowFieldExecution {
        override fun requestShadowFieldExecution(
            parameters: InstrumentationFieldParameters,
            state: InstrumentationState?,
        ): ShadowFieldExecutionComparison? = throw throwable
    }
}
