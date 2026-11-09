package viaduct.engine.runtime.execution

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.createEngineObjectData
import viaduct.engine.api.mocks.runFeatureTest
import viaduct.engine.runtime.Engine1QueryExecutionContract
import viaduct.graphql.test.assertJson

@ExperimentalCoroutinesApi
class BatchFieldResolverContractTest : BatchFieldResolverContract, Engine1QueryExecutionContract {
    @Test
    fun `field batch resolver does not read from dataloader cache`() {
        val execCounts = ConcurrentHashMap<String, AtomicInteger>()
        EngineTestModule(BatchFieldResolverContract.schemaSDL) {
            field("Query" to "items") {
                resolver {
                    fn { _, _, _, _, _ ->
                        listOf(1, 2).map { i ->
                            createEngineObjectData(
                                schema.schema.getObjectType("Item"),
                                mapOf("x" to i)
                            )
                        }
                    }
                }
            }
            field("Query" to "anotherItem") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(
                            schema.schema.getObjectType("Item"),
                            mapOf("x" to 1)
                        )
                    }
                }
            }
            field("Item" to "y") {
                val resolverId = resolverId
                resolver {
                    objectSelections("x")
                    fn { selectors, _ ->
                        selectors.associateWith { selector ->
                            execCounts.computeIfAbsent(resolverId) { AtomicInteger(0) }.incrementAndGet()
                            val x = selector.syncObjectValueGetter().get("x") as Int
                            Result.success(x)
                        }
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{ items { x y } anotherItem { x y }}")
                .assertJson("""{"data": {"items": [{ "x": 1, "y": 1 }, { "x": 2, "y": 2 }], "anotherItem": { "x": 1, "y": 1 }}}""")
        }
        // We disable caching for field data loaders, so the execCounts value is 3. Otherwise, it would be 2.
        assertEquals(mapOf("Item.y" to 3), execCounts.mapValues { it.value.get() })
    }
}
