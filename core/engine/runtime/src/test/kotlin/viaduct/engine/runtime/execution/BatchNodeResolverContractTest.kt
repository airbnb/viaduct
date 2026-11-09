package viaduct.engine.runtime.execution

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.createEngineObjectData
import viaduct.engine.api.mocks.runFeatureTest
import viaduct.engine.runtime.Engine1QueryExecutionContract

@ExperimentalCoroutinesApi
class BatchNodeResolverContractTest : BatchNodeResolverContract, Engine1QueryExecutionContract {
    @Test
    @Disabled("Flaky, disabling until fix; see TODO")
    override fun `node batch resolver throws`() {
        super<BatchNodeResolverContract>.`node batch resolver throws`()
    }

    @Test
    fun `node resolver reads from dataloader cache`() {
        val execCounts = ConcurrentHashMap<String, AtomicInteger>()
        EngineTestModule(BatchNodeResolverContract.schemaSDL) {
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
                            createEngineObjectData(
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
        EngineTestModule(BatchNodeResolverContract.schemaSDL) {
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
                            createEngineObjectData(
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
        EngineTestModule(BatchNodeResolverContract.schemaSDL) {
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
                            createEngineObjectData(
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
        EngineTestModule(BatchNodeResolverContract.schemaSDL) {
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
                            createEngineObjectData(
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
