package viaduct.engine.runtime

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import graphql.GraphqlErrorBuilder
import graphql.execution.DataFetcherResult
import graphql.execution.instrumentation.InstrumentationState
import graphql.execution.instrumentation.parameters.InstrumentationFieldParameters
import graphql.schema.DataFetcher
import graphql.schema.FieldCoordinates
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.slf4j.LoggerFactory
import viaduct.deferred.currentRequestParentJobOrNull
import viaduct.engine.EngineConfiguration
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineSchema
import viaduct.engine.api.ParentManagedValue
import viaduct.engine.api.StandardResolutionValue
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
import viaduct.engine.runtime.execution.FieldResolver

@OptIn(ExperimentalCoroutinesApi::class)
class ShadowFieldExecutionTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `fatal shadow resolver failure is logged without cancelling production`(wrapped: Boolean) {
        val fatalError = NoSuchMethodError("missing shadow resolver method")
        val failure = if (wrapped) CompletionException(fatalError) else fatalError
        val resolverCalls = AtomicInteger()
        val comparisonCalls = AtomicInteger()
        val productionRequestJob = CompletableDeferred<Job?>()
        val shadowStarted = CompletableDeferred<Unit>()
        val failShadow = CompletableDeferred<Unit>()
        val events = captureShadowFailures { failureLogged ->
            val instrumentation = ShadowFieldExecutionComparisonInstrumentation(
                coordinate = "Query" to "value",
                observer = { comparisonCalls.incrementAndGet() },
            )
            EngineTestModule(
                """
                extend type Query {
                    value: String!
                    shadowFinished: Boolean!
                }
                """.trimIndent()
            ) {
                field("Query" to "value") {
                    resolver {
                        fn { _, _, _, _, _ ->
                            resolverCalls.incrementAndGet()
                            if (currentRequestParentJobOrNull() === productionRequestJob.await()) {
                                "production"
                            } else {
                                shadowStarted.complete(Unit)
                                failShadow.await()
                                throw failure
                            }
                        }
                    }
                }
                field("Query" to "shadowFinished") {
                    resolver {
                        fn { _, _, _, _, _ ->
                            productionRequestJob.complete(currentRequestParentJobOrNull())
                            shadowStarted.await()
                            failShadow.complete(Unit)
                            // Keep production active while the shadow failure reaches its logging boundary.
                            withTimeoutOrNull(1_000) { failureLogged.await() }
                            true
                        }
                    }
                }
            }.runFeatureTest(
                engineConfig = EngineConfiguration.featureTestDefault.copy(
                    additionalInstrumentation = instrumentation.asStandardInstrumentation,
                )
            ) {
                runQueryWithin("{ value shadowFinished }", timeoutMillis = 5_000)
                    .assertJson("""{data: {value: "production", shadowFinished: true}}""")
            }
        }

        assertEquals(2, resolverCalls.get())
        assertEquals(0, comparisonCalls.get())
        assertEquals(1, events.size, "Expected the fatal shadow failure to be logged exactly once")
        val loggedCause = generateSequence(events.single().throwableProxy) { it.cause }.last()
        assertEquals(NoSuchMethodError::class.java.name, loggedCause.className)
        assertEquals(fatalError.message, loggedCause.message)
    }

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
            fieldWithValue("Query" to "value", "production")
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
            fieldWithValue("Query" to "value", "production")
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

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `request supervisor isolates shadow comparison failures`(fatal: Boolean) {
        val failure = if (fatal) AssertionError("fatal comparison failure") else CancellationException("comparison cancelled")
        val comparisonCalls = AtomicInteger()
        val comparisonCompleted = CompletableDeferred<Unit>()
        val instrumentation =
            ShadowFieldExecutionComparisonInstrumentation(
                coordinate = "Query" to "value",
                observer = {
                    comparisonCalls.incrementAndGet()
                    comparisonCompleted.complete(Unit)
                    throw failure
                },
            )

        val events = captureShadowFailures {
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
                fieldWithValue("Query" to "value", "production")
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
        }

        assertEquals(1, comparisonCalls.get())
        assertEquals(if (fatal) 1 else 0, events.size)
    }

    private fun captureShadowFailures(block: (CompletableDeferred<Unit>) -> Unit): List<ILoggingEvent> {
        val failureLogged = CompletableDeferred<Unit>()
        val events = ConcurrentLinkedQueue<ILoggingEvent>()
        val logger = LoggerFactory.getLogger(FieldResolver::class.java) as Logger
        val appender = object : AppenderBase<ILoggingEvent>() {
            override fun append(event: ILoggingEvent) {
                if (event.level == Level.ERROR && event.message == "Unexpected failure while executing shadow field at {}") {
                    events.add(event)
                    failureLogged.complete(Unit)
                }
            }
        }
        appender.start()
        logger.addAppender(appender)
        try {
            block(failureLogged)
            return events.toList()
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    private fun schemaWithClassicFetcher(): EngineSchema {
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

        return EngineSchema(
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
