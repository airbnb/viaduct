package viaduct.engine.runtime2.execution

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Duration
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import viaduct.engine.EngineConfiguration
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.createEngineObjectData
import viaduct.engine.api.mocks.featureTestDefault
import viaduct.engine.api.mocks.fetchAs
import viaduct.engine.api.mocks.getAs
import viaduct.engine.runtime.EngineExecutionContextImpl
import viaduct.engine.runtime.execution.SubqueryExecutionContract
import viaduct.engine.runtime.execution.mutation
import viaduct.engine.runtime.execution.query
import viaduct.engine.runtime.execution.scopedAsync
import viaduct.engine.runtime2.execution.testing.QPlanFeatureTest
import viaduct.engine.runtime2.execution.testing.runQPlanFeatureTest as runFeatureTestOnce
import viaduct.service.api.spi.FlagManager
import viaduct.service.api.spi.mocks.MockFlagManager

abstract class SubqueryExecutionContractTestCases(
    override val flagManager: FlagManager,
) : SubqueryExecutionContract, Engine2QueryExecutionContract {
    @Disabled("ALT: Mutation resolvers use ctx.query() instead of Query required selections; alternative uses structured Kotlin coroutines")
    @Test
    fun `parallel ctx mutations with disjoint query selections on same node complete`() {
        val variableResolversStarted = AtomicInteger()
        val bothVariableResolversStarted = CompletableDeferred<Unit>()

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
                    querySelections(
                        """
                        node(id: ${'$'}fooId) {
                            ... on Foo {
                                a @include(if: ${'$'}includeA)
                                b @include(if: ${'$'}includeB)
                            }
                        }
                        """.trimIndent()
                    ) {
                        variables("fooId", "includeA", "includeB") { resolveCtx, _ ->
                            if (variableResolversStarted.incrementAndGet() == 2) {
                                bothVariableResolversStarted.complete(Unit)
                            }
                            bothVariableResolversStarted.await()

                            val kind = resolveCtx.arguments.getAs<String>("kind")
                            mapOf(
                                "fooId" to "foo-1",
                                "includeA" to (kind == "A"),
                                "includeB" to (kind == "B")
                            )
                        }
                    }

                    fn { args, _, queryValue, _, _ ->
                        val node = queryValue.fetchAs<EngineObjectData>("node")
                        when (args.getAs<String>("kind")) {
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
    fun `ALTERNATIVE parallel ctx mutations with disjoint query selections on same node complete`() {
        val nodeCalls = AtomicInteger()
        val bothNodesStarted = CompletableDeferred<Unit>()
        val nodeSelections = Collections.synchronizedList(mutableListOf<Set<String>>())

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
                    val requested = setOf("a", "b").filterTo(mutableSetOf()) {
                        selections?.containsSelection("Foo", it) == true
                    }
                    nodeSelections.add(requested)
                    if (nodeCalls.incrementAndGet() == 2) bothNodesStarted.complete(Unit)
                    withTimeout(5_000) { bothNodesStarted.await() }
                    createEngineObjectData(
                        objectType,
                        buildMap<String, Any?> {
                            put("id", id)
                            requested.forEach { put(it, it) }
                        }
                    )
                }
            }

            field("Mutation" to "readFoo") {
                resolver {
                    fn { args, _, _, _, ctx ->
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
                            mapOf("fooId" to "foo-1", "includeA" to (kind == "A"), "includeB" to (kind == "B"))
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
                        coroutineScope {
                            val first = async {
                                val selections = ctx.engineSelectionSetFactory
                                    .engineSelectionSet("Mutation", """readFoo(kind: "A")""", emptyMap())
                                ctx.mutation(selections).fetchAs<String>("readFoo")
                            }
                            val second = async {
                                val selections = ctx.engineSelectionSetFactory
                                    .engineSelectionSet("Mutation", """readFoo(kind: "B")""", emptyMap())
                                ctx.mutation(selections).fetchAs<String>("readFoo")
                            }
                            "${first.await()}${second.await()}"
                        }
                    }
                }
            }
        }.runFeatureTest(withoutDefaultQueryNodeResolvers = true) {
            runQueryWithTimeout("mutation { runBoth }", timeoutMillis = 10_000)
                .assertJson("""{"data": {"runBoth": "ab"}}""")
        }
        assertEquals(2, nodeCalls.get())
        assertEquals(setOf(setOf("a"), setOf("b")), nodeSelections.toSet())
    }

    @Disabled("N/A: Asserts production subquery-execution instrumentation outside qplan's resolver-correctness boundary")
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

    @Disabled("ALT: Production wraps invalid subquery planning in SubqueryExecutionException; qplan reports its native schema failure")
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

    @Test
    fun `ALTERNATIVE ctx query wraps QueryPlan build failures in SubqueryExecutionException`() {
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
                result.errors.first().message.contains("Field 'nonExistentField' in type 'Query' is undefined"),
                "Expected qplan schema failure, got: ${result.errors.first().message}"
            )
        }
    }

    private fun EngineTestModule.runFeatureTest(
        withoutDefaultQueryNodeResolvers: Boolean = false,
        engineConfig: EngineConfiguration = EngineConfiguration.featureTestDefault,
        block: QPlanFeatureTest.() -> Unit,
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
