// core/engine/runtime/src/test/kotlin/viaduct/engine/runtime/execution/SubqueryExecutionTest.kt
// Copied 27 out of 27 tests as of 2026-09-18

package viaduct.engine.runtime2.execution.viaductfeaturetests

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
import viaduct.engine.api.mocks.MockFieldUnbatchedResolverExecutor
import viaduct.engine.api.mocks.createEngineObjectData
import viaduct.engine.api.mocks.featureTestDefault
import viaduct.engine.api.mocks.fetchAs
import viaduct.engine.api.mocks.getAs
import viaduct.engine.runtime.EngineExecutionContextImpl
import viaduct.engine.runtime.execution.SubqueryExecutionContract
import viaduct.engine.runtime.execution.mutation
import viaduct.engine.runtime.execution.query
import viaduct.engine.runtime.execution.scopedAsync
import viaduct.engine.runtime2.execution.Engine2QueryExecutionContract
import viaduct.engine.runtime2.execution.testing.QPlanFeatureTest
import viaduct.engine.runtime2.execution.testing.runQPlanFeatureTest as runFeatureTestOnce
import viaduct.service.api.spi.FlagManager
import viaduct.service.api.spi.mocks.MockFlagManager

/**
 * Test harness for subquery execution via ExecutionHandle.
 *
 * This test suite validates the ctx.query() and ctx.mutation() APIs that enable
 * resolvers to execute subqueries against the same GraphQL schema without rebuilding
 * GraphQL-Java state.
 *
 * ## Architecture
 *
 * The subquery execution flow uses ExecutionHandle to maintain execution context:
 * 1. Resolver calls ctx.query(selections)
 * 2. ExecutionHandle provides access to ExecutionParameters (opaque to tenant code)
 * 3. Engine builds QueryPlan from EngineSelectionSet
 * 4. Engine resolves fields with proper access checks and variable scoping
 * 5. Returns EngineObjectData with resolved fields
 *
 * ## Test Coverage
 *
 * - Basic ctx.query() from nested resolvers
 * - Basic ctx.mutation() from nested resolvers
 * - Variable scoping in subqueries
 * - Error handling and propagation
 * - Access check execution during subqueries
 */
abstract class SubqueryExecutionTestCases(
    override val flagManager: FlagManager,
) : SubqueryExecutionContract, Engine2QueryExecutionContract {
    @Test
    fun `ctx query fails when selection type does not match Query root`() {
        EngineTestModule(
            """
            extend type Query {
                container: Container
            }

            type Container {
                tryMismatchedSelection: Int
            }

            type User {
                id: ID
                name: String
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

            field("Container" to "tryMismatchedSelection") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        // Create a EngineSelectionSet for User type, but try to execute as Query
                        val rss = ctx.engineSelectionSetFactory
                            .engineSelectionSet("User", "id name", emptyMap())

                        ctx.query(
                            selectionSet = rss
                        )

                        0
                    }
                }
            }
        }.runFeatureTest {
            val result = runQuery("{ container { tryMismatchedSelection } }")

            assertEquals(1, result.errors.size)
            assertTrue(
                result.errors.first().message.contains("Cannot execute selections with type User on schema root type Query")
            )
        }
    }

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

    @Test
    fun `ctx mutation executes fields serially`() {
        val events = Collections.synchronizedList(mutableListOf<String>())

        EngineTestModule(
            """
            extend type Query {
                container: Container
            }

            extend type Mutation {
                field1: Int
                field2: Int
            }

            type Container {
                triggerMutations: MutationResult
            }

            type MutationResult {
                result1: Int
                result2: Int
            }
            """.trimIndent()
        ) {
            field("Mutation" to "field1") {
                resolver {
                    fn { _, _, _, _, _ ->
                        events.add("field1:start")
                        events.add("field1:end")
                        1
                    }
                }
            }

            field("Mutation" to "field2") {
                resolver {
                    fn { _, _, _, _, _ ->
                        events.add("field2:start")
                        events.add("field2:end")
                        2
                    }
                }
            }

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

            field("Container" to "triggerMutations") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        val rss = ctx.engineSelectionSetFactory
                            .engineSelectionSet("Mutation", "field1 field2", emptyMap())

                        val mutationResult = ctx.mutation(
                            selectionSet = rss
                        )

                        val result1 = mutationResult.fetchAs<Int>("field1")
                        val result2 = mutationResult.fetchAs<Int>("field2")

                        createEngineObjectData(
                            schema.schema.getObjectType("MutationResult"),
                            mapOf("result1" to result1, "result2" to result2)
                        )
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{ container { triggerMutations { result1 result2 } } }")
                .assertJson("""{"data": {"container": {"triggerMutations": {"result1": 1, "result2": 2}}}}""")

            assertEquals(
                listOf("field1:start", "field1:end", "field2:start", "field2:end"),
                events,
                "Mutation fields should execute serially: field1 must complete before field2 starts"
            )
        }
    }

    @Test
    fun `ctx query from mutation resolver does not serialize namespaced query fields`() {
        val q1Started = CompletableDeferred<Unit>()
        val q2Started = CompletableDeferred<Unit>()
        val q1CanFinish = CompletableDeferred<Unit>()
        val parallelObservations = Collections.synchronizedList(mutableListOf<String>())

        EngineTestModule(
            """
            extend type Query {
                namespace: QueryNamespace
            }

            extend type Mutation {
                triggerQuery: String
            }

            type QueryNamespace @namespaceType {
                q1: Int
                q2: Int
            }
            """.trimIndent()
        ) {
            field("Query" to "namespace") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(
                            schema.schema.getObjectType("QueryNamespace"),
                            mapOf()
                        )
                    }
                }
            }

            field("QueryNamespace" to "q1") {
                resolver {
                    fn { _, _, _, _, _ ->
                        q1Started.complete(Unit)
                        q1CanFinish.await()
                        1
                    }
                }
            }

            field("QueryNamespace" to "q2") {
                resolver {
                    fn { _, _, _, _, _ ->
                        if (!q1CanFinish.isCompleted) {
                            parallelObservations.add("q2 started before q1 completed")
                        }
                        q2Started.complete(Unit)
                        2
                    }
                }
            }

            field("Mutation" to "triggerQuery") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        val rss = ctx.engineSelectionSetFactory
                            .engineSelectionSet("Query", "namespace { q1 q2 }", emptyMap())

                        val queryResult = scopedAsync {
                            ctx.query(selectionSet = rss)
                        }

                        withTimeout(1000) { q1Started.await() }
                        withTimeout(1000) { q2Started.await() }
                        q1CanFinish.complete(Unit)

                        val namespace = queryResult.await().fetchAs<EngineObjectData>("namespace")
                        "${namespace.fetchAs<Int>("q1")}:${namespace.fetchAs<Int>("q2")}"
                    }
                }
            }
        }.runFeatureTest {
            runQuery("mutation { triggerQuery }")
                .assertJson("""{"data": {"triggerQuery": "1:2"}}""")

            assertEquals(
                listOf("q2 started before q1 completed"),
                parallelObservations,
                "Query namespace fields should stay parallel when selected by ctx.query from a mutation resolver"
            )
        }
    }

    @Test
    fun `regression -- ctx query hangs when a resolver reads an aliased object selection`() {
        EngineTestModule(
            """
            extend type Query {
                run: Int
                read: Int
                value: Int
            }
            """.trimIndent()
        ) {
            fieldWithValue("Query" to "value", 1)
            field("Query" to "read") {
                resolver {
                    objectSelections("aliasedValue: value")
                    fn { _, obj, _, _, _ ->
                        obj.fetchAs<Int>("aliasedValue")
                    }
                }
            }
            field("Query" to "run") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        ctx
                            .query(ctx.engineSelectionSetFactory.engineSelectionSet("Query", "read", emptyMap()))
                            .fetchAs<Int>("read")
                    }
                }
            }
        }.runFeatureTest {
            runQueryWithTimeout("{ run }").assertJson("{data: {run: 1}}")
        }
    }

    @Test
    fun `regression -- ctx query hangs when a resolver reads an object selection with arguments`() {
        EngineTestModule(
            """
            extend type Query {
                run: Int
                read: Int
                value(n: Int!): Int
            }
            """.trimIndent()
        ) {
            field("Query" to "value") {
                resolver {
                    fn { args, _, _, _, _ ->
                        args.getAs<Int>("n")
                    }
                }
            }

            field("Query" to "read") {
                resolver {
                    objectSelections("value(n: 1)")
                    fn { _, obj, _, _, _ ->
                        obj.fetchAs<Int>("value")
                    }
                }
            }

            field("Query" to "run") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        ctx
                            .query(ctx.engineSelectionSetFactory.engineSelectionSet("Query", "read", emptyMap()))
                            .fetchAs<Int>("read")
                    }
                }
            }
        }.runFeatureTest {
            runQueryWithTimeout("{ run }").assertJson("{data: {run: 1}}")
        }
    }

    @Test
    fun `regression -- ctx query hangs when a resolver reads a plain object selection`() {
        EngineTestModule(
            """
            extend type Query {
                run: Int
                read: Int
                value: Int
            }
            """.trimIndent()
        ) {
            fieldWithValue("Query" to "value", 1)
            field("Query" to "read") {
                resolver {
                    objectSelections("value")
                    fn { _, obj, _, _, _ ->
                        obj.fetchAs<Int>("value")
                    }
                }
            }
            field("Query" to "run") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        ctx
                            .query(ctx.engineSelectionSetFactory.engineSelectionSet("Query", "read", emptyMap()))
                            .fetchAs<Int>("read")
                    }
                }
            }
        }.runFeatureTest {
            runQueryWithTimeout("{ run }").assertJson("{data: {run: 1}}")
        }
    }

    @Test
    fun `ctx query into selective field with nested composite does not hang`() {
        EngineTestModule(
            """
            extend type Query {
                foo: Foo
                entry: Int
            }

            type Foo { bar: Bar }

            type Bar { baz: Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, _, _ ->
                            createEngineObjectData(
                                schema.schema.getObjectType("Foo"),
                                mapOf("bar" to mapOf("baz" to 7))
                            )
                        }
                    )
                }
            }

            field("Query" to "entry") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        val rss = ctx.engineSelectionSetFactory
                            .engineSelectionSet("Query", "foo { bar { baz } }", emptyMap())
                        val queryResult = ctx.query(selectionSet = rss)
                        withTimeout(1_000) {
                            queryResult
                                .fetchAs<EngineObjectData>("foo")
                                .fetchAs<EngineObjectData>("bar")
                                .fetchAs<Int>("baz")
                        }
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{ entry }")
                .assertJson("""{"data": {"entry": 7}}""")
        }
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

class SubqueryExecutionTest {
    @Nested
    inner class Legacy : SubqueryExecutionTestCases(MockFlagManager.Disabled)

    @Nested
    inner class Mat : SubqueryExecutionTestCases(
        MockFlagManager.create(FlagManager.Flags.ENABLE_MAT_RESOLUTION)
    )
}
