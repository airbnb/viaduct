package viaduct.engine.runtime

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import viaduct.engine.api.mocks.MockTenantModuleBootstrapper
import viaduct.engine.api.mocks.mkEngineObjectData
import viaduct.engine.api.mocks.runFeatureTest

@ExperimentalCoroutinesApi
class BatchNodeResolverTest {
    companion object {
        private val schemaSDL = """
            extend type Query {
                baz: Baz
                bazList: [Baz!]!
            }
            type Baz implements Node {
                id: ID!
                x: Int
                x2: String
                anotherBaz: Baz
            }
        """.trimIndent()
    }

    @Test
    fun `node batch resolver returns value`() {
        MockTenantModuleBootstrapper(schemaSDL) {
            field("Query" to "baz") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        ctx.createNodeReference("1", schema.schema.getObjectType("Baz"))
                    }
                }
            }
            type("Baz") {
                nodeBatchedExecutor { selectors, _ ->
                    assert(selectors.size == 1) { "Expected exactly 1 ctx" }
                    selectors.associateWith { selector ->
                        Result.success(
                            mkEngineObjectData(
                                objectType,
                                mapOf("id" to selector.id, "x" to 20)
                            )
                        )
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{baz {x}}")
                .assertJson("""{"data": {"baz": {"x": 20}}}""")
        }
    }

    @Test
    fun `node batch resolver batches`() {
        MockTenantModuleBootstrapper(schemaSDL) {
            field("Query" to "bazList") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        (1..3).map { i ->
                            ctx.createNodeReference(i.toString(), schema.schema.getObjectType("Baz"))
                        }
                    }
                }
            }
            type("Baz") {
                nodeBatchedExecutor { selectors, _ ->
                    selectors.associateWith { selector ->
                        Result.success(
                            mkEngineObjectData(
                                objectType,
                                mapOf(
                                    "id" to selector.id,
                                    "x" to selectors.size // x is the number of items in the batch, x > 1 indicates successful batching
                                )
                            )
                        )
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{bazList {id x}}")
                .assertJson("""{"data": {"bazList": [{"id":"1", "x":3}, {"id":"2", "x":3}, {"id":"3", "x":3}]}}""")
        }
    }

    @Test
    @Disabled("Flaky, disabling until fix; see TODO")
    fun `node batch resolver throws`() {
        MockTenantModuleBootstrapper(schemaSDL) {
            field("Query" to "bazList") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        (1..3).map { i ->
                            ctx.createNodeReference(i.toString(), schema.schema.getObjectType("Baz"))
                        }
                    }
                }
            }
            type("Baz") {
                nodeBatchedExecutor { _, _ ->
                    throw RuntimeException("baz fail")
                }
            }
        }.runFeatureTest {
            val result = runQuery("{ bazList { x }}")
            assertEquals(null as Any?, result.getData())
            assertEquals(3, result.errors.size)
            result.errors.forEachIndexed { idx, error ->
                assertEquals(listOf("bazList", idx), error.path)
                assertTrue(error.message.contains("baz fail"))
                assertEquals("DataFetchingException", error.errorType.toString())
            }
        }
    }

    @Test
    fun `node batch resolver returns partial errors`() {
        MockTenantModuleBootstrapper(schemaSDL) {
            field("Query" to "bazList") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        (1..3).map { i ->
                            ctx.createNodeReference(i.toString(), schema.schema.getObjectType("Baz"))
                        }
                    }
                }
            }
            type("Baz") {
                nodeBatchedExecutor { selectors, _ ->
                    selectors.associateWith { selector ->
                        if (selector.id == "2") {
                            Result.failure(IllegalArgumentException("Odd idx for ID: ${selector.id}"))
                        } else {
                            Result.success(
                                mkEngineObjectData(
                                    objectType,
                                    mapOf("id" to selector.id)
                                )
                            )
                        }
                    }
                }
            }
        }.runFeatureTest {
            val result = runQuery("{ bazList { id }}")
            assertEquals(null as Any?, result.getData())
            assertEquals(1, result.errors.size)
            val error = result.errors[0]
            assertEquals(listOf("bazList", 1), error.path)
            assertTrue(error.message.contains("Odd idx for ID: 2"))
            assertEquals("DataFetchingException", error.errorType.toString())
        }
    }

    @Test
    fun `node resolver reads from dataloader cache`() {
        val execCounts = ConcurrentHashMap<String, AtomicInteger>()
        MockTenantModuleBootstrapper(schemaSDL) {
            field("Query" to "baz") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        ctx.createNodeReference("1", schema.schema.getObjectType("Baz"))
                    }
                }
            }
            field("Baz" to "anotherBaz") {
                resolver {
                    objectSelections("x")
                    fn { _, objectValue, _, _, ctx ->
                        // Make this wait for the first Baz node resolver to be dispatched
                        objectValue.fetch("x")
                        ctx.createNodeReference("1", schema.schema.getObjectType("Baz"))
                    }
                }
            }
            type("Baz") {
                nodeBatchedExecutor { selectors, _ ->
                    selectors.associateWith { selector ->
                        val internalId = selector.id
                        execCounts.computeIfAbsent(internalId) { AtomicInteger(0) }.incrementAndGet()
                        Result.success(
                            mkEngineObjectData(
                                objectType,
                                mapOf("id" to selector.id, "x" to 2)
                            )
                        )
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{ baz { x anotherBaz { id x }}}")
                .assertJson("""{"data": {"baz": {"x":2, "anotherBaz":{"id":"1", "x":2}}}}""")
        }

        assertEquals(mapOf("1" to 1), execCounts.mapValues { it.value.get() })
    }

    @Test
    fun `non-selective node batch resolver reads from dataloader cache for different selection sets`() {
        // Non-selective resolvers always return their full output regardless of requested fields,
        // so caching by ID alone is correct - same ID = cache hit
        val execCounts = ConcurrentHashMap<String, AtomicInteger>()
        MockTenantModuleBootstrapper(schemaSDL) {
            field("Query" to "baz") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        ctx.createNodeReference("1", schema.schema.getObjectType("Baz"))
                    }
                }
            }
            field("Baz" to "anotherBaz") {
                resolver {
                    objectSelections("x")
                    fn { _, objectValue, _, _, ctx ->
                        // Make this wait for the first Baz node resolver to be dispatched
                        objectValue.fetch("x")
                        ctx.createNodeReference("1", schema.schema.getObjectType("Baz"))
                    }
                }
            }
            type("Baz") {
                nodeBatchedExecutor { selectors, _ ->
                    selectors.associateWith { selector ->
                        val internalId = selector.id
                        execCounts.computeIfAbsent(internalId) { AtomicInteger(0) }.incrementAndGet()
                        Result.success(
                            mkEngineObjectData(
                                objectType,
                                mapOf("id" to selector.id, "x" to 2, "x2" to "foo")
                            )
                        )
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{ baz { x anotherBaz { x x2 }}}")
                .assertJson("""{"data": {"baz": {"x":2, "anotherBaz":{"x":2, "x2":"foo"}}}}""")
        }

        // Non-selective resolver caches by ID only, so second request for same ID uses cache
        assertEquals(mapOf("1" to 1), execCounts.mapValues { it.value.get() })
    }

    @Test
    fun `selective node batch resolver does not read from dataloader cache for different selection sets`() {
        // Selective resolvers tailor their response based on requested fields,
        // so different selection sets for the same ID should NOT use cache
        val execCounts = ConcurrentHashMap<String, AtomicInteger>()
        MockTenantModuleBootstrapper(schemaSDL) {
            field("Query" to "baz") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        ctx.createNodeReference("1", schema.schema.getObjectType("Baz"))
                    }
                }
            }
            field("Baz" to "anotherBaz") {
                resolver {
                    objectSelections("x")
                    fn { _, objectValue, _, _, ctx ->
                        // Make this wait for the first Baz node resolver to be dispatched
                        objectValue.fetch("x")
                        ctx.createNodeReference("1", schema.schema.getObjectType("Baz"))
                    }
                }
            }
            type("Baz") {
                nodeBatchedExecutor(selective = true) { selectors, _ ->
                    selectors.associateWith { selector ->
                        val internalId = selector.id
                        execCounts.computeIfAbsent(internalId) { AtomicInteger(0) }.incrementAndGet()
                        Result.success(
                            mkEngineObjectData(
                                objectType,
                                mapOf("id" to selector.id, "x" to 2, "x2" to "foo")
                            )
                        )
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{ baz { x anotherBaz { x x2 }}}")
                .assertJson("""{"data": {"baz": {"x":2, "anotherBaz":{"x":2, "x2":"foo"}}}}""")
        }

        // Selective resolver checks selection sets, so different selections = cache miss
        assertEquals(mapOf("1" to 2), execCounts.mapValues { it.value.get() })
    }

    @Test
    fun `selective node batch resolver reads from dataloader cache for same selection sets`() {
        // Selective resolvers should still cache when selection sets are the same
        val execCounts = ConcurrentHashMap<String, AtomicInteger>()
        MockTenantModuleBootstrapper(schemaSDL) {
            field("Query" to "baz") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        ctx.createNodeReference("1", schema.schema.getObjectType("Baz"))
                    }
                }
            }
            field("Baz" to "anotherBaz") {
                resolver {
                    objectSelections("x")
                    fn { _, objectValue, _, _, ctx ->
                        // Make this wait for the first Baz node resolver to be dispatched
                        objectValue.fetch("x")
                        ctx.createNodeReference("1", schema.schema.getObjectType("Baz"))
                    }
                }
            }
            type("Baz") {
                nodeBatchedExecutor(selective = true) { selectors, _ ->
                    selectors.associateWith { selector ->
                        val internalId = selector.id
                        execCounts.computeIfAbsent(internalId) { AtomicInteger(0) }.incrementAndGet()
                        Result.success(
                            mkEngineObjectData(
                                objectType,
                                mapOf("id" to selector.id, "x" to 2)
                            )
                        )
                    }
                }
            }
        }.runFeatureTest {
            // Both requests for the same ID ask for the same fields (id, x)
            runQuery("{ baz { x anotherBaz { id x }}}")
                .assertJson("""{"data": {"baz": {"x":2, "anotherBaz":{"id":"1", "x":2}}}}""")
        }

        // Same selection sets = cache hit, even for selective resolver
        assertEquals(mapOf("1" to 1), execCounts.mapValues { it.value.get() })
    }
}
