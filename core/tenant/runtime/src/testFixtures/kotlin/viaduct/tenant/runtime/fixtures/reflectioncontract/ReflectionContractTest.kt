package viaduct.tenant.runtime.fixtures.reflectioncontract

import org.junit.jupiter.api.Test
import viaduct.graphql.test.assertEquals
import viaduct.tenant.runtime.fixtures.FeatureAppTestBase

/**
 * Contract test for resolvers that use ctx.selections() reflection API.
 *
 * Defines the SDL and assertions for:
 * - Static reflective types: requestsType() on union member types
 * - Dynamic reflective types: only id field requested, no products
 *
 * Extend this class and provide resolver implementations to verify that a given
 * runtime correctly supports these patterns.
 */
abstract class ReflectionContractTest : FeatureAppTestBase() {
    init {
        sdl = """
            | #START_SCHEMA
            | union Product = Toy | Fruit
            |
            | type Category {
            |   id: Int!
            |   "Use ctx.selections() to check requested types; return [Toy(id=category.id, prodType=\"Toy\"), Fruit(id=category.id, prodType=\"Fruit\")] for each requested union member"
            |   products: [Product] @resolver(isSelective: true)
            | }
            |
            | extend type Query {
            |   "Return Category with id=<id argument>"
            |   category(id: Int!): Category @resolver
            | }
            |
            | type Toy {
            |   id: Int!
            |   prodType: String
            | }
            |
            | type Fruit {
            |   id: Int!
            |   prodType: String
            | }
            |
            | #END_SCHEMA
        """.trimMargin()
    }

    @Test
    fun `static reflective types work`() {
        execute(
            query = """
                query {
                    category(id: 123) {
                        id
                        products {
                            ... on Toy {
                                id
                                prodType
                            }
                            ... on Fruit {
                                id
                                prodType
                            }
                        }
                    }
                }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                "category" to {
                    "id" to 123
                    "products" to arrayOf(
                        {
                            "id" to 123
                            "prodType" to "Toy"
                        },
                        {
                            "id" to 123
                            "prodType" to "Fruit"
                        }
                    )
                }
            }
        }
    }

    @Test
    fun `dynamic reflective types work`() {
        execute(
            query = """
                query {
                    category(id: 123) {
                        id
                    }
                }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                "category" to {
                    "id" to 123
                }
            }
        }
    }
}
