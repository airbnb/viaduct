package viaduct.tenant.runtime.execution.batchresolver.fieldresolver

import org.junit.jupiter.api.Test
import viaduct.api.FieldValue
import viaduct.api.Resolver
import viaduct.graphql.test.assertEquals
import viaduct.tenant.runtime.execution.batchresolver.fieldresolver.resolverbases.ItemResolvers
import viaduct.tenant.runtime.execution.batchresolver.fieldresolver.resolverbases.QueryResolvers
import viaduct.tenant.runtime.fixtures.FeatureAppTestBase

class FieldBatchResolverFeatureAppTest : FeatureAppTestBase() {
    override var sdl = """
        | #START_SCHEMA
        | extend type Query {
        |   items(count: Int = 2): [Item] @resolver
        | }
        |
        | type Item {
        |   id: String!
        |   batchedField: String @resolver
        |   listField: [Item] @resolver
        | }
        | #END_SCHEMA
    """.trimMargin()

    @Resolver
    class Query_ItemsResolver : QueryResolvers.Items() {
        override suspend fun resolve(ctx: Context): List<Item> {
            val count = ctx.arguments.count ?: 2
            return (1..count).map { i ->
                Item.Builder(ctx)
                    .id("item-$i")
                    .build()
            }
        }
    }

    @Resolver(
        objectValueFragment = "fragment _ on Item { id }",
    )
    class Item_BatchedFieldResolver : ItemResolvers.BatchedField() {
        override suspend fun batchResolve(contexts: List<Context>): List<FieldValue<String>> {
            return contexts.map { ctx ->
                val itemId = ctx.objectValue.getId()
                FieldValue.Companion.ofValue("batched-$itemId-size-${contexts.size}")
            }
        }
    }

    @Resolver(
        objectValueFragment = "fragment _ on Item { id }",
    )
    class Item_ListFieldResolver : ItemResolvers.ListField() {
        override suspend fun batchResolve(contexts: List<Context>): List<FieldValue<List<Item>>> {
            return contexts.map { ctx ->
                val itemId = ctx.objectValue.getId()
                FieldValue.Companion.ofValue(
                    (1..contexts.size).map { i ->
                        Item.Builder(ctx)
                            .id("$itemId-list-$i-size-${contexts.size}")
                            .build()
                    }
                )
            }
        }
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
