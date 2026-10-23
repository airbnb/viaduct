package viaduct.engine.runtime.execution

import graphql.ExecutionResult
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import viaduct.engine.EngineConfiguration
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.ExecutionInput
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.FeatureTest
import viaduct.engine.api.mocks.createEngineObjectData
import viaduct.engine.api.mocks.featureTestDefault
import viaduct.engine.api.mocks.fetchAs
import viaduct.engine.api.mocks.getAs
import viaduct.engine.api.mocks.runFeatureTest as runFeatureTestOnce
import viaduct.engine.runtime.EngineExecutionContextImpl
import viaduct.service.api.spi.FlagManager
import viaduct.service.api.spi.mocks.MockFlagManager

abstract class SubqueryExecutionContractTestCases(
    override val flagManager: FlagManager,
) : SubqueryExecutionContract, viaduct.engine.runtime.Engine1QueryExecutionContract {
    @Test
    fun `parallel ctx mutations with disjoint query selections on same node complete`() {
        val queryResolversStarted = AtomicInteger()
        val bothQueryResolversStarted = CompletableDeferred<Unit>()

        EngineTestModule(
            """
            extend type Mutation {
                runBoth: String
                readFoo(kind: String!): String
            }

            type Foo implements Node {
                id: ID!
                a: String
                b: String
            }
            """.trimIndent()
        ) {
            field("Query" to "node") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        ctx.createNodeReference("foo-1", schema.schema.getObjectType("Foo"))
                    }
                }
            }

            type("Foo") {
                nodeUnbatchedExecutor(selective = true) { id, selections, _ ->
                    delay(100)
                    val values = mutableMapOf<String, Any?>("id" to id)
                    if (selections?.containsSelection("Foo", "a") == true) {
                        values["a"] = "a"
                    }
                    if (selections?.containsSelection("Foo", "b") == true) {
                        values["b"] = "b"
                    }
                    createEngineObjectData(
                        objectType,
                        values
                    )
                }
            }

            field("Mutation" to "readFoo") {
                resolver {
                    fn { args, _, _, _, ctx ->
                        if (queryResolversStarted.incrementAndGet() == 2) {
                            bothQueryResolversStarted.complete(Unit)
                        }
                        bothQueryResolversStarted.await()
                        val kind = args.getAs<String>("kind")
                        val selections = ctx.engineSelectionSetFactory.engineSelectionSet(
                            "Query",
                            """
                            node(id: ${'$'}fooId) {
                                ... on Foo {
                                    a @include(if: ${'$'}includeA)
                                    b @include(if: ${'$'}includeB)
                                }
                            }
                            """.trimIndent(),
                            mapOf(
                                "fooId" to "foo-1",
                                "includeA" to (kind == "A"),
                                "includeB" to (kind == "B")
                            )
                        )
                        val node = ctx.query(selections).fetchAs<EngineObjectData>("node")
                        when (kind) {
                            "A" -> node.fetchAs<String>("a")
                            "B" -> node.fetchAs<String>("b")
                            else -> error("Unexpected kind")
                        }
                    }
                }
            }

            field("Mutation" to "runBoth") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        val first = scopedAsync {
                            val selectionSet = ctx.engineSelectionSetFactory
                                .engineSelectionSet("Mutation", """readFoo(kind: "A")""", emptyMap())
                            ctx.mutation(selectionSet = selectionSet).fetchAs<String>("readFoo")
                        }
                        val second = scopedAsync {
                            val selectionSet = ctx.engineSelectionSetFactory
                                .engineSelectionSet("Mutation", """readFoo(kind: "B")""", emptyMap())
                            ctx.mutation(selectionSet = selectionSet).fetchAs<String>("readFoo")
                        }
                        "${first.await()}${second.await()}"
                    }
                }
            }
        }.runFeatureTest(withoutDefaultQueryNodeResolvers = true) {
            assertTimeoutPreemptively(Duration.ofSeconds(5)) {
                runQuery("mutation { runBoth }")
                    .assertJson("""{"data": {"runBoth": "ab"}}""")
            }
        }
    }

    @Test
    fun `subquery execution emits metric`() {
        val meterRegistry = SimpleMeterRegistry()
        val engineConfig = EngineConfiguration.featureTestDefault.copy(
            meterRegistry = meterRegistry
        )

        EngineTestModule(
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
                        createEngineObjectData(
                            schema.schema.getObjectType("Container"),
                            mapOf()
                        )
                    }
                }
            }

            field("Container" to "derivedFromQuery") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        val rss = ctx.engineSelectionSetFactory
                            .engineSelectionSet("Query", "rootValue", emptyMap())
                        val queryResult = ctx.query(
                            selectionSet = rss
                        )
                        queryResult.fetchAs<Int>("rootValue") * 2
                    }
                }
            }
        }.runFeatureTest(engineConfig = engineConfig) {
            runQuery("{ container { derivedFromQuery } }")
                .assertJson("""{"data": {"container": {"derivedFromQuery": 84}}}""")

            val counter = meterRegistry.find(EngineExecutionContextImpl.SUBQUERY_EXECUTION_METER_NAME)
                .tag("success", "true")
                .counter()
            assertNotNull(counter, "Expected subquery execution counter with success=true to be present")
            assertEquals(1.0, counter!!.count(), "Expected exactly one successful subquery execution")

            val failureCounter = meterRegistry.find(EngineExecutionContextImpl.SUBQUERY_EXECUTION_METER_NAME)
                .tag("success", "false")
                .counter()
            assertTrue(failureCounter == null || failureCounter.count() == 0.0, "Expected no failed subquery executions")
        }
    }

    @Test
    fun `ctx query wraps QueryPlan build failures in SubqueryExecutionException`() {
        EngineTestModule(
            """
            extend type Query {
                container: Container
            }

            type Container {
                result: Int
            }
            """.trimIndent()
        ) {
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

            field("Container" to "result") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        // Selection references a field that doesn't exist on Query
                        val rss = ctx.engineSelectionSetFactory
                            .engineSelectionSet("Query", "nonExistentField", emptyMap())

                        ctx.query(
                            selectionSet = rss
                        )

                        0
                    }
                }
            }
        }.runFeatureTest {
            val result = runQuery("{ container { result } }")

            assertEquals(1, result.errors.size)
            assertTrue(
                result.errors.first().message.contains("Failed to build QueryPlan"),
                "Expected queryPlanBuildFailed error, got: ${result.errors.first().message}"
            )
        }
    }

    private fun FeatureTest.runQueryWithTimeout(
        query: String,
        variables: Map<String, Any?> = emptyMap(),
        timeoutMillis: Long = 1000,
    ): ExecutionResult {
        val input = ExecutionInput(
            operationText = query,
            variables = variables,
            requestContext = Any(),
        )
        return kotlinx.coroutines.runBlocking {
            withTimeout(timeoutMillis) {
                DefaultCoroutineInterop.enterThreadLocalCoroutineContext(coroutineContext) {
                    engine.execute(input)
                }.await()
            }
        }
    }

    private fun EngineTestModule.runFeatureTest(
        withoutDefaultQueryNodeResolvers: Boolean = false,
        engineConfig: EngineConfiguration = EngineConfiguration.featureTestDefault,
        block: FeatureTest.() -> Unit,
    ) {
        runFeatureTestOnce(
            withoutDefaultQueryNodeResolvers = withoutDefaultQueryNodeResolvers,
            engineConfig = engineConfig.copy(flagManager = flagManager),
            block = block,
        )
    }
}

class SubqueryExecutionContractTest {
    @Nested
    inner class Legacy : SubqueryExecutionContractTestCases(MockFlagManager.Disabled)

    @Nested
    inner class Mat : SubqueryExecutionContractTestCases(
        MockFlagManager.create(FlagManager.Flags.ENABLE_MAT_RESOLUTION)
    )
}
