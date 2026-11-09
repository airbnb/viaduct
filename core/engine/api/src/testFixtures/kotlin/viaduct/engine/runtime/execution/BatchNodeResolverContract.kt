package viaduct.engine.runtime.execution

import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.createEngineObjectData
import viaduct.graphql.test.assertJson

@ExperimentalCoroutinesApi
interface BatchNodeResolverContract : QueryExecutionContract {
    companion object {
        val schemaSDL = """
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
        EngineTestModule(schemaSDL) {
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
                            createEngineObjectData(
                                objectType,
                                mapOf("id" to selector.id, "x" to 20)
                            )
                        )
                    }
                }
            }
        }.runContractTest {
            runQuery("{baz {x}}")
                .assertJson("""{"data": {"baz": {"x": 20}}}""")
        }
    }

    @Test
    fun `node batch resolver batches`() {
        EngineTestModule(schemaSDL) {
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
                            createEngineObjectData(
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
        }.runContractTest {
            runQuery("{bazList {id x}}")
                .assertJson("""{"data": {"bazList": [{"id":"1", "x":3}, {"id":"2", "x":3}, {"id":"3", "x":3}]}}""")
        }
    }

    @Test
    fun `node batch resolver throws`() {
        EngineTestModule(schemaSDL) {
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
        }.runContractTest {
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
        EngineTestModule(schemaSDL) {
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
                                createEngineObjectData(
                                    objectType,
                                    mapOf("id" to selector.id)
                                )
                            )
                        }
                    }
                }
            }
        }.runContractTest {
            val result = runQuery("{ bazList { id }}")
            assertEquals(null as Any?, result.getData())
            assertEquals(1, result.errors.size)
            val error = result.errors[0]
            assertEquals(listOf("bazList", 1), error.path)
            assertTrue(error.message.contains("Odd idx for ID: 2"))
            assertEquals("DataFetchingException", error.errorType.toString())
        }
    }
}
