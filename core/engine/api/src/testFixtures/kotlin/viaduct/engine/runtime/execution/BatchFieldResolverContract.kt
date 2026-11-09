package viaduct.engine.runtime.execution

import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.createEngineObjectData
import viaduct.graphql.test.assertJson

@ExperimentalCoroutinesApi
interface BatchFieldResolverContract : QueryExecutionContract {
    companion object {
        val schemaSDL = """
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
        val bootstrapper = EngineTestModule(schemaSDL) {
            field("Query" to "items") {
                resolver {
                    fn { arguments, _, _, _, _ ->
                        val size = arguments["size"] as? Int ?: 1
                        (1..size).map { i ->
                            createEngineObjectData(
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
                            Result.success(selector.syncObjectValueGetter().get("x") as Int + 1)
                        }
                    }
                }
            }
        }
        bootstrapper.runContractTest {
            runQuery("{ items(size: 1) { x y }}")
                .assertJson("""{"data": {"items": [{ "x": 1, "y": 2 }]}}""")
        }
        bootstrapper.runContractTest {
            runQuery("{ items { x y }}")
                .assertJson("""{"data": {"items": [{ "x": 1, "y": 2 }, { "x": 2, "y": 3 }]}}""")
        }
    }

    @Test
    fun `field batch resolver batches in a tick`() {
        EngineTestModule(schemaSDL) {
            field("Query" to "items") {
                resolver {
                    fn { arguments, _, _, _, _ ->
                        val size = arguments["size"] as? Int ?: 1
                        (1..size).map { i ->
                            createEngineObjectData(
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
        }.runContractTest {
            runQuery("{ items { x y }}")
                .assertJson("""{"data": {"items": [{ "x": 1, "y": 2 }, { "x": 2, "y": 2 }]}}""")
        }
    }

    @Test
    fun `field batch resolver throws`() {
        EngineTestModule(schemaSDL) {
            field("Query" to "items") {
                resolver {
                    fn { arguments, _, _, _, _ ->
                        val size = arguments["size"] as? Int ?: 1
                        (1..size).map { i ->
                            createEngineObjectData(
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
        }.runContractTest {
            runQuery("{ items { x y }}")
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
        EngineTestModule(schemaSDL) {
            field("Query" to "items") {
                resolver {
                    fn { arguments, _, _, _, _ ->
                        val size = arguments["size"] as? Int ?: 1
                        (1..size).map { i ->
                            createEngineObjectData(
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
                            val x = selector.syncObjectValueGetter().get("x") as Int
                            if (x % 2 == 0) {
                                Result.failure(IllegalArgumentException("Even idx for item: $x"))
                            } else {
                                Result.success(x)
                            }
                        }
                    }
                }
            }
        }.runContractTest {
            runQuery("{ items { x y }}")
                .apply {
                    assertEquals(1, errors.size)
                    val error = errors.first()
                    assertEquals(listOf("items", 1, "y"), error.path)
                    assertTrue(error.message.contains("Even idx for item"))
                    assertEquals("DataFetchingException", error.errorType.toString())
                }.getData<Map<String, Any?>>().assertJson("""{"items": [{"x": 1, "y": 1}, {"x": 2, "y": null}]}""")
        }
    }
}
