package viaduct.tenant.runtime.execution.fieldbatch

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.api.testing.TestSchema
import viaduct.api.testing.featureapp.KotlinFeatureAppTestContractBase
import viaduct.graphql.test.assertEquals

/**
 * Contract test for field batch resolver patterns.
 *
 * Defines the SDL and assertions for:
 * - Field batch resolver batches multiple field requests
 * - Field batch resolver works with single item
 * - Field batch resolver returns a list of entity object data (EOD)
 *
 * Extend this class and provide resolver implementations to verify that a given
 * runtime correctly supports these patterns.
 */
@TestSchema(
    """
    extend type Query {
      "Return a list of <count> Items with ids \"item-1\" through \"item-<count>\""
      items(count: Int = 2): [Item] @resolver
    }

    type Item {
      id: String!
      "Batch resolver: return \"batched-<item.id>-size-<batch_size>\" where batch_size is the total items in the batch"
      batchedField: String @resolver(isBatching: true)
      outcomeField(failBatch: Boolean = false): String @resolver(isBatching: true)
      "Batch resolver returning list: return 2 Items per parent with ids \"<parent.id>-list-1-size-<batch_size>\", \"<parent.id>-list-2-size-<batch_size>\""
      listField: [Item]  @resolver(isBatching: true)
    }
"""
)
abstract class FieldBatchResolverContractTest : KotlinFeatureAppTestContractBase() {
    @Test
    fun `batch item errors preserve successful and null siblings`() {
        val result = execute(query = "{ items(count: 3) { id outcomeField } }")

        assertEquals(
            mapOf(
                "items" to listOf(
                    mapOf("id" to "item-1", "outcomeField" to "success"),
                    mapOf("id" to "item-2", "outcomeField" to null),
                    mapOf("id" to "item-3", "outcomeField" to null),
                )
            ),
            result.getData(),
        )
        assertEquals(1, result.errors.size)
        assertEquals(listOf("items", 2, "outcomeField"), result.errors.single().path)
        assertTrue(result.errors.single().message.contains("item failed"))
    }

    @Test
    fun `batch invocation failure reports every affected field`() {
        val result = execute(query = "{ items(count: 2) { id outcomeField(failBatch: true) } }")

        assertEquals(
            mapOf(
                "items" to listOf(
                    mapOf("id" to "item-1", "outcomeField" to null),
                    mapOf("id" to "item-2", "outcomeField" to null),
                )
            ),
            result.getData(),
        )
        assertEquals(2, result.errors.size)
        assertEquals(setOf(listOf("items", 0, "outcomeField"), listOf("items", 1, "outcomeField")), result.errors.map { it.path }.toSet())
        assertTrue(result.errors.all { it.message.contains("batch failed") })
    }

    @Test
    fun `field batch resolver batches multiple field requests`() {
        execute(
            query = """
                query {
                    items(count: 3) {
                        id
                        batchedField
                    }
                }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                "items" to arrayOf(
                    {
                        "id" to "item-1"
                        "batchedField" to "batched-item-1-size-3"
                    },
                    {
                        "id" to "item-2"
                        "batchedField" to "batched-item-2-size-3"
                    },
                    {
                        "id" to "item-3"
                        "batchedField" to "batched-item-3-size-3"
                    }
                )
            }
        }
    }

    @Test
    fun `field batch resolver works with single item`() {
        execute(
            query = """
                query {
                    items(count: 1) {
                        id
                        batchedField
                    }
                }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                "items" to arrayOf(
                    {
                        "id" to "item-1"
                        "batchedField" to "batched-item-1-size-1"
                    }
                )
            }
        }
    }

    @Test
    fun `field batch resolver returns list of EOD`() {
        execute(
            query = """
                query {
                    items(count: 2) {
                        id
                        listField {
                            id
                        }
                    }
                }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                "items" to arrayOf(
                    {
                        "id" to "item-1"
                        "listField" to arrayOf(
                            {
                                "id" to "item-1-list-1-size-2"
                            },
                            {
                                "id" to "item-1-list-2-size-2"
                            }
                        )
                    },
                    {
                        "id" to "item-2"
                        "listField" to arrayOf(
                            {
                                "id" to "item-2-list-1-size-2"
                            },
                            {
                                "id" to "item-2-list-2-size-2"
                            }
                        )
                    }
                )
            }
        }
    }
}
