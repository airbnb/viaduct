package viaduct.engine.runtime

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.api.mocks.MockTenantModuleBootstrapper
import viaduct.engine.api.mocks.mkEngineObjectData
import viaduct.engine.api.mocks.runFeatureTest
import viaduct.graphql.test.assertJson

@ExperimentalCoroutinesApi
class BatchFieldResolverTest {
    companion object {
        private val schemaSDL = """
            extend type Query {
                items(size: Int = 2): [Item!]!
                anotherItem: Item
            }
            type Item {
                x: Int
                y: Int
            }
        """.trimIndent()
    }

    @Test
    fun `field batch resolver returns value`() {
        val bootstrapper = MockTenantModuleBootstrapper(schemaSDL) {
            field("Query" to "items") {
                resolver {
                    fn { arguments, _, _, _, _ ->
                        val size = arguments["size"] as? Int ?: 1
                        (1..size).map { i ->
                            mkEngineObjectData(
                                schema.schema.getObjectType("Item"),
                                mapOf("x" to i)
                            )
                        }
                    }
                }
            }
            field("Item" to "y") {
                resolver {
                    objectSelections("x")
                    fn { selectors, _ ->
                        selectors.associateWith { selector ->
                            Result.success(selector.objectValue.fetch("x") as Int + 1)
                        }
                    }
                }
            }
        }
        bootstrapper.runFeatureTest {
            viaduct.runQuery("{ items(size: 1) { x y }}")
                .assertJson("""{"data": {"items": [{ "x": 1, "y": 2 }]}}""")
        }
        bootstrapper.runFeatureTest {
            viaduct.runQuery("{ items { x y }}")
                .assertJson("""{"data": {"items": [{ "x": 1, "y": 2 }, { "x": 2, "y": 3 }]}}""")
        }
    }

    @Test
    fun `field batch resolver batches in a tick`() {
        MockTenantModuleBootstrapper(schemaSDL) {
            field("Query" to "items") {
                resolver {
                    fn { arguments, _, _, _, _ ->
                        val size = arguments["size"] as? Int ?: 1
                        (1..size).map { i ->
                            mkEngineObjectData(
                                schema.schema.getObjectType("Item"),
                                mapOf("x" to i)
                            )
                        }
                    }
                }
            }
            field("Item" to "y") {
                resolver {
                    fn { selectors, _ ->
                        selectors.associateWith { _ ->
                            Result.success(selectors.size) // selectors.size is the number of items in the batch, larger than 1 indicates successful batching
                        }
                    }
                }
            }
        }.runFeatureTest {
            viaduct.runQuery("{ items { x y }}")
                .assertJson("""{"data": {"items": [{ "x": 1, "y": 2 }, { "x": 2, "y": 2 }]}}""")
        }
    }

    @Test
    fun `field batch resolver throws`() {
        MockTenantModuleBootstrapper(schemaSDL) {
            field("Query" to "items") {
                resolver {
                    fn { arguments, _, _, _, _ ->
                        val size = arguments["size"] as? Int ?: 1
                        (1..size).map { i ->
                            mkEngineObjectData(
                                schema.schema.getObjectType("Item"),
                                mapOf("x" to i)
                            )
                        }
                    }
                }
            }
            field("Item" to "y") {
                resolver {
                    fn { _, _ ->
                        throw RuntimeException("Item y resolver failed")
                    }
                }
            }
        }.runFeatureTest {
            viaduct.runQuery("{ items { x y }}")
                .apply {
                    assertEquals(2, errors.size)
                    errors.forEachIndexed { idx, error ->
                        assertEquals(listOf("items", idx, "y"), error.path)
                        assertTrue(error.message.contains("Item y resolver failed"))
                        assertEquals("DataFetchingException", error.errorType.toString())
                    }
                }.getData<Map<String, Any?>>().assertJson("""{"items": [{"x": 1, "y": null}, {"x": 2, "y": null}]}""")
        }
    }

    @Test
    fun `field batch resolver returns partial errors`() {
        MockTenantModuleBootstrapper(schemaSDL) {
            field("Query" to "items") {
                resolver {
                    fn { arguments, _, _, _, _ ->
                        val size = arguments["size"] as? Int ?: 1
                        (1..size).map { i ->
                            mkEngineObjectData(
                                schema.schema.getObjectType("Item"),
                                mapOf("x" to i)
                            )
                        }
                    }
                }
            }
            field("Item" to "y") {
                resolver {
                    objectSelections("x")
                    fn { selectors, _ ->
                        selectors.associateWith { selector ->
                            val x = selector.objectValue.fetch("x") as Int
                            if (x % 2 == 0) {
                                Result.failure(IllegalArgumentException("Even idx for item: $x"))
                            } else {
                                Result.success(x)
                            }
                        }
                    }
                }
            }
        }.runFeatureTest {
            viaduct.runQuery("{ items { x y }}")
                .apply {
                    assertEquals(1, errors.size)
                    val error = errors.first()
                    assertEquals(listOf("items", 1, "y"), error.path)
                    assertTrue(error.message.contains("Even idx for item"))
                    assertEquals("DataFetchingException", error.errorType.toString())
                }.getData<Map<String, Any?>>().assertJson("""{"items": [{"x": 1, "y": 1}, {"x": 2, "y": null}]}""")
        }
    }

    @Test
    fun `field batch resolver does not read from dataloader cache`() {
        val execCounts = ConcurrentHashMap<String, AtomicInteger>()
        MockTenantModuleBootstrapper(schemaSDL) {
            field("Query" to "items") {
                resolver {
                    fn { _, _, _, _, _ ->
                        listOf(1, 2).map { i ->
                            mkEngineObjectData(
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
                        mkEngineObjectData(
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
                            val x = selector.objectValue.fetch("x") as Int
                            Result.success(x)
                        }
                    }
                }
            }
        }.runFeatureTest {
            viaduct.runQuery("{ items { x y } anotherItem { x y }}")
                .assertJson("""{"data": {"items": [{ "x": 1, "y": 1 }, { "x": 2, "y": 2 }], "anotherItem": { "x": 1, "y": 1 }}}""")
        }
        // We disable caching for field data loaders, so the execCounts value is 3. Otherwise, it would be 2.
        assertEquals(mapOf("Item.y" to 3), execCounts.mapValues { it.value.get() })
    }
}
