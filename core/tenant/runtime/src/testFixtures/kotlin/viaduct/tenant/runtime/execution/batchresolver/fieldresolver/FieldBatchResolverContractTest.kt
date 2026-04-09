package viaduct.tenant.runtime.execution.batchresolver.fieldresolver

import org.junit.jupiter.api.Test
import viaduct.api.testing.TestSchema
import viaduct.graphql.test.assertEquals
import viaduct.tenant.runtime.fixtures.FeatureAppTestBase

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
      items(count: Int = 2): [Item] @resolver
    }

    type Item {
      id: String!
      batchedField: String @resolver
      listField: [Item] @resolver
    }
"""
)
abstract class FieldBatchResolverContractTest : FeatureAppTestBase() {
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
