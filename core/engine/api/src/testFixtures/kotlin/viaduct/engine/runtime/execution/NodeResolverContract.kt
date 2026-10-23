package viaduct.engine.runtime.execution

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.createEngineObjectData
import viaduct.graphql.test.assertJson

@ExperimentalCoroutinesApi
interface NodeResolverContract : QueryExecutionContract {
    val schemaSDL get() = """
            extend type Query {
                baz: Baz
                bazList: [Baz]!
            }
            type Baz implements Node {
                id: ID!
                x: Int
                x2: String
                y: String
                z: Int
                anotherBaz: Baz
            }
    """.trimIndent()

    enum class Behavior { RETURNS_VALUE, ID_ONLY, THROWS }

    data class ExecutionOutcome(val result: graphql.ExecutionResult, val nodeResolverCalled: Boolean)

    fun execute(behavior: Behavior): ExecutionOutcome {
        var nodeResolverCalled = false
        val module = EngineTestModule(schemaSDL) {
            field("Query" to "baz") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        ctx.createNodeReference("1", requireNotNull(schema.schema.getObjectType("Baz")))
                    }
                }
            }
            type("Baz") {
                nodeUnbatchedExecutor { _, _, _ ->
                    nodeResolverCalled = true
                    when (behavior) {
                        Behavior.RETURNS_VALUE -> createEngineObjectData(objectType, mapOf("x" to 42))
                        Behavior.ID_ONLY -> createEngineObjectData(objectType, emptyMap())
                        Behavior.THROWS -> throw RuntimeException("msg")
                    }
                }
            }
        }
        val query = if (behavior == Behavior.ID_ONLY) "{ baz { id } }" else "{ baz { x } }"
        return ExecutionOutcome(executeQuery(module, query), nodeResolverCalled)
    }

    @Test
    fun `node resolver returns value`() {
        val outcome = execute(Behavior.RETURNS_VALUE)
        assertEquals(mapOf("baz" to mapOf("x" to 42)), outcome.result.toSpecification()["data"])
        assertTrue(outcome.result.errors.isEmpty())
        assertTrue(outcome.nodeResolverCalled)
    }

    @Test
    fun `node resolver runs for id only selection`() {
        val outcome = execute(Behavior.ID_ONLY)
        assertTrue(outcome.nodeResolverCalled)
        assertTrue(outcome.result.errors.isEmpty())
    }

    @Test
    fun `node resolver failure appears at node path`() {
        val outcome = execute(Behavior.THROWS)
        assertEquals(mapOf("baz" to null), outcome.result.toSpecification()["data"])
        assertTrue(outcome.result.errors.any { it.path == listOf("baz") })
    }

    @Test
    fun `node reference nested inside resolver response`() {
        runQueryTest(
            EngineTestModule(schemaSDL) {
                field("Query" to "baz") {
                    resolver {
                        fn { _, _, _, _, ctx ->
                            createEngineObjectData(
                                requireNotNull(schema.schema.getObjectType("Baz")),
                                mapOf(
                                    "x" to 1,
                                    "anotherBaz" to
                                        ctx.createNodeReference(
                                            "2",
                                            requireNotNull(schema.schema.getObjectType("Baz")),
                                        ),
                                ),
                            )
                        }
                    }
                }
                type("Baz") {
                    nodeUnbatchedExecutor { _, _, _ ->
                        createEngineObjectData(
                            objectType,
                            mapOf("x" to 99),
                        )
                    }
                }
            }
        ) { runQuery ->
            runQuery("{baz { x anotherBaz { x } }}")
                .assertJson("""{"data": {"baz": {"x": 1, "anotherBaz": {"x": 99}}}}""")
        }
    }

    @Test
    fun `node field executes in parallel with node resolver`() {
        var yInvoked = false
        runQueryTest(
            EngineTestModule(schemaSDL) {
                field("Query" to "baz") {
                    resolver {
                        fn { _, _, _, _, ctx ->
                            ctx.createNodeReference("1", requireNotNull(schema.schema.getObjectType("Baz")))
                        }
                    }
                }
                field("Baz" to "y") {
                    resolver {
                        fn { _, _, _, _, _ ->
                            yInvoked = true
                            "a"
                        }
                    }
                }
                type("Baz") {
                    nodeUnbatchedExecutor { _, _, _ ->
                        delay(50)
                        throw RuntimeException("msg")
                    }
                }
            }
        ) { runQuery ->
            val result = runQuery("{ baz { y } }")
            assertTrue(yInvoked)
            assertEquals(mapOf("baz" to null), result.getData())
            assertTrue(result.errors.size == 1)
            assertEquals(listOf(listOf("baz")), result.errors.map { it.path })
        }
    }

    @Test
    fun `awaits completion for node in required selection set`() {
        runQueryTest(
            EngineTestModule(schemaSDL) {
                field("Query" to "baz") {
                    resolver {
                        fn { _, _, _, _, ctx ->
                            ctx.createNodeReference("1", requireNotNull(schema.schema.getObjectType("Baz")))
                        }
                    }
                }
                type("Baz") {
                    nodeUnbatchedExecutor { id, _, _ ->
                        if (id == "2") {
                            delay(50)
                            throw RuntimeException("expected err")
                        } else {
                            createEngineObjectData(objectType, mapOf("x" to 1))
                        }
                    }
                }
                field("Baz" to "anotherBaz") {
                    resolver {
                        fn { _, _, _, _, ctx ->
                            ctx.createNodeReference("2", requireNotNull(schema.schema.getObjectType("Baz")))
                        }
                    }
                }
                field("Baz" to "z") {
                    resolver {
                        objectSelections("anotherBaz { id }")
                        fn { _, objectValue, _, _, _ ->
                            // The point of this test is that this should wait the node resolver for
                            // `anotherBaz` to execute rather than immediately returning what's available,
                            // as we do when the required selection set is on the node itself. Since
                            // `anotherBaz` will resolve with an exception, this `fetch` call should throw.
                            objectValue.fetch("anotherBaz")
                            5
                        }
                    }
                }
            }
        ) { runQuery ->
            val result = runQuery("{ baz { z } }")
            assertEquals(mapOf("baz" to mapOf("z" to null)), result.getData())
            assertEquals(listOf(listOf("baz", "z")), result.errors.map { it.path })
            assertTrue(result.errors[0].message.contains("expected err"))
        }
    }

    @Test
    fun `list of nodes`() {
        runQueryTest(
            EngineTestModule(schemaSDL) {
                field("Query" to "bazList") {
                    resolver {
                        fn { _, _, _, _, ctx ->
                            (1..5).map {
                                ctx.createNodeReference(
                                    it.toString(),
                                    requireNotNull(schema.schema.getObjectType("Baz")),
                                )
                            }
                        }
                    }
                }
                type("Baz") {
                    nodeUnbatchedExecutor { id, _, _ ->
                        val internalId = id.toInt()
                        if (internalId % 2 == 0) {
                            throw RuntimeException("msg")
                        } else {
                            createEngineObjectData(objectType, mapOf("x" to internalId))
                        }
                    }
                }
            }
        ) { runQuery ->
            val result = runQuery("{ bazList { x } }")
            val expectedResultData =
                mapOf(
                    "bazList" to
                        listOf(
                            mapOf("x" to 1),
                            null,
                            mapOf("x" to 3),
                            null,
                            mapOf("x" to 5),
                        ),
                )
            assertEquals(expectedResultData, result.getData())
            assertEquals(
                listOf(listOf("bazList", 1), listOf("bazList", 3)),
                result.errors.map { it.path },
            )
        }
    }

    @Test
    fun `node resolver does not batch`() {
        val execCounts = ConcurrentHashMap<String, AtomicInteger>()
        runQueryTest(
            EngineTestModule(schemaSDL) {
                field("Query" to "bazList") {
                    resolver {
                        fn { _, _, _, _, ctx ->
                            (1..5).map {
                                ctx.createNodeReference(
                                    it.toString(),
                                    requireNotNull(schema.schema.getObjectType("Baz")),
                                )
                            }
                        }
                    }
                }
                type("Baz") {
                    nodeUnbatchedExecutor { id, _, _ ->
                        val internalId = id
                        execCounts
                            .computeIfAbsent(internalId) { AtomicInteger(0) }
                            .incrementAndGet()
                        createEngineObjectData(
                            objectType,
                            mapOf("x" to internalId.toInt()),
                        )
                    }
                }
            }
        ) { runQuery ->
            val result = runQuery("{bazList { x }}")

            // Verify each node was resolved individually (not batched)
            assertEquals(
                mapOf("1" to 1, "2" to 1, "3" to 1, "4" to 1, "5" to 1),
                execCounts.mapValues { it.value.get() },
            )

            // Verify the results are correct
            val expectedData =
                mapOf(
                    "bazList" to
                        listOf(
                            mapOf("x" to 1),
                            mapOf("x" to 2),
                            mapOf("x" to 3),
                            mapOf("x" to 4),
                            mapOf("x" to 5),
                        ),
                )
            assertEquals(expectedData, result.getData())
        }
    }

    @Test
    fun `node resolver reads from dataloader cache`() {
        val execCounts = ConcurrentHashMap<String, AtomicInteger>()
        runQueryTest(
            EngineTestModule(schemaSDL) {
                field("Query" to "baz") {
                    resolver {
                        fn { _, _, _, _, ctx ->
                            ctx.createNodeReference("1", requireNotNull(schema.schema.getObjectType("Baz")))
                        }
                    }
                }
                field("Baz" to "anotherBaz") {
                    resolver {
                        fn { _, _, _, _, ctx ->
                            ctx.createNodeReference("1", requireNotNull(schema.schema.getObjectType("Baz")))
                        }
                    }
                }
                type("Baz") {
                    nodeUnbatchedExecutor { id, _, _ ->
                        val internalId = id
                        execCounts.computeIfAbsent(internalId) { AtomicInteger(0) }.incrementAndGet()
                        createEngineObjectData(objectType, mapOf("x" to 2))
                    }
                }
            }
        ) { runQuery ->
            runQuery("{ baz { x anotherBaz { id x }}}")
                .assertJson("""{"data": {"baz": {"x":2, "anotherBaz":{"id":"1", "x":2}}}}""")

            assertEquals(mapOf("1" to 1), execCounts.mapValues { it.value.get() })
        }
    }

    @Test
    fun `non-selective node resolver reads from dataloader cache for different selection sets`() {
        // Non-selective resolvers always return their full output regardless of requested fields,
        // so caching by ID alone is correct - same ID = cache hit.
        // Looped to catch concurrency regressions in the dataloader cache.
        repeat(100) {
            val execCounts = ConcurrentHashMap<String, AtomicInteger>()
            runQueryTest(
                EngineTestModule(schemaSDL) {
                    field("Query" to "baz") {
                        resolver {
                            fn { _, _, _, _, ctx ->
                                ctx.createNodeReference("1", requireNotNull(schema.schema.getObjectType("Baz")))
                            }
                        }
                    }
                    field("Baz" to "anotherBaz") {
                        resolver {
                            fn { _, _, _, _, ctx ->
                                ctx.createNodeReference("1", requireNotNull(schema.schema.getObjectType("Baz")))
                            }
                        }
                    }
                    type("Baz") {
                        nodeUnbatchedExecutor { id, _, _ ->
                            execCounts.computeIfAbsent(id) { AtomicInteger(0) }.incrementAndGet()
                            createEngineObjectData(objectType, mapOf("x" to 2, "x2" to "foo"))
                        }
                    }
                }
            ) { runQuery ->
                runQuery("{ baz { x anotherBaz { x x2 }}}")
                    .assertJson("""{"data": {"baz": {"x":2, "anotherBaz":{"x":2, "x2":"foo"}}}}""")

                assertEquals(mapOf("1" to 1), execCounts.mapValues { it.value.get() })
            }
        }
    }

    @Test
    fun `selective node resolver does not read from dataloader cache if selection set does not cover`() {
        // Selective resolvers tailor their response based on requested fields,
        // so different selection sets for the same ID should NOT use cache
        val execCounts = ConcurrentHashMap<String, AtomicInteger>()
        runQueryTest(
            EngineTestModule(schemaSDL) {
                field("Query" to "baz") {
                    resolver {
                        fn { _, _, _, _, ctx ->
                            ctx.createNodeReference("1", requireNotNull(schema.schema.getObjectType("Baz")))
                        }
                    }
                }
                field("Baz" to "anotherBaz") {
                    resolver {
                        fn { _, _, _, _, ctx ->
                            ctx.createNodeReference("1", requireNotNull(schema.schema.getObjectType("Baz")))
                        }
                    }
                }
                type("Baz") {
                    nodeUnbatchedExecutor(selective = true) { id, _, _ ->
                        execCounts.computeIfAbsent(id) { AtomicInteger(0) }.incrementAndGet()
                        createEngineObjectData(objectType, mapOf("x" to 2, "x2" to "foo"))
                    }
                }
            }
        ) { runQuery ->
            runQuery("{ baz { x anotherBaz { x x2 }}}")
                .assertJson("""{"data": {"baz": {"x":2, "anotherBaz":{"x":2, "x2":"foo"}}}}""")

            // Selective resolver checks selection sets, so different selections = cache miss
            assertEquals(mapOf("1" to 2), execCounts.mapValues { it.value.get() })
        }
    }

    @Test
    fun `node reference list with single item failure`() {
        runQueryTest(
            EngineTestModule(schemaSDL) {
                field("Query" to "bazList") {
                    resolver {
                        fn { _, _, _, _, ctx ->
                            listOf(
                                ctx.createNodeReference("ok", requireNotNull(schema.schema.getObjectType("Baz"))),
                                ctx.createNodeReference("fail", requireNotNull(schema.schema.getObjectType("Baz"))),
                            )
                        }
                    }
                }
                type("Baz") {
                    nodeUnbatchedExecutor { id, _, _ ->
                        if (id == "fail") {
                            throw RuntimeException("node resolution failed for $id")
                        }
                        createEngineObjectData(objectType, mapOf("x" to 7))
                    }
                }
            }
        ) { runQuery ->
            val result = runQuery("{ bazList { x } }")
            val data = requireNotNull(result.getData<Map<String, Any?>>())
            val bazList = data["bazList"] as List<*>
            assertEquals(2, bazList.size)
            assertEquals(mapOf("x" to 7), bazList[0])
            assertEquals(null, bazList[1])
            assertTrue(result.errors.any { it.message.contains("node resolution failed for fail") })
        }
    }

    @Test
    fun `node resolver not executed twice for the same query path`() {
        // This is a regression test for NodeEngineObjectDataImpl.resolveData() calling the
        // underlying node resolver each time it's called when it should only call it once.
        val execCount = AtomicInteger(0)
        runQueryTest(
            EngineTestModule(schemaSDL) {
                field("Query" to "baz") {
                    resolver {
                        fn { _, _, _, _, ctx ->
                            ctx.createNodeReference("1", requireNotNull(schema.schema.getObjectType("Baz")))
                        }
                    }
                }
                type("Baz") {
                    nodeUnbatchedExecutor { _, _, _ ->
                        execCount.incrementAndGet()
                        createEngineObjectData(objectType, mapOf("x" to 10))
                    }
                }
                field("Baz" to "x2") {
                    resolver {
                        querySelections("baz { x }")
                        fn { _, _, queryValue, _, _ ->
                            (queryValue.fetch("baz") as EngineObjectData).fetch("x")
                        }
                    }
                }
            }
        ) { runQuery ->
            runQuery("{ baz { x x2 }}")
                .assertJson("""{"data": {"baz": {"x":10, "x2":"10"}}}""")

            assertEquals(1, execCount.get())
        }
    }
}
