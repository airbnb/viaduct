package viaduct.tenant.runtime.fixtures

import org.junit.jupiter.api.Test
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
abstract class FieldBatchResolverContractTest : FeatureAppTestBase() {
    init {
        sdl = """
            | #START_SCHEMA
            | extend type Query {
            |   "Return a list of <count> Items with ids \"item-1\" through \"item-<count>\""
            |   items(count: Int = 2): [Item] @resolver
            | }
            |
            | type Item {
            |   id: String!
            |   "Batch resolver: return \"batched-<item.id>-size-<batch_size>\" where batch_size is the total items in the batch"
            |   batchedField: String @resolver
            |   "Batch resolver returning list: return 2 Items per parent with ids \"<parent.id>-list-1-size-<batch_size>\", \"<parent.id>-list-2-size-<batch_size>\""
            |   listField: [Item] @resolver
            | }
            | #END_SCHEMA
        """.trimMargin()
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
