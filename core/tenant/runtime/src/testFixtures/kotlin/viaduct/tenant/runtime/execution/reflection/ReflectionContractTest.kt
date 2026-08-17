package viaduct.tenant.runtime.execution.reflection

import org.junit.jupiter.api.Test
import viaduct.api.testing.TestSchema
import viaduct.api.testing.featureapp.KotlinFeatureAppTestContractBase
import viaduct.graphql.test.assertEquals

/**
 * Contract test for resolvers that use the ctx.selections() and ctx.ownedSelections()
 * reflection APIs.
 *
 * Defines the SDL and assertions for:
 * - Static reflective types: requestsType() on union member types
 * - Dynamic reflective types: only id field requested, no products
 *
 * Extend this class and provide resolver implementations to verify that a given
 * runtime correctly supports these patterns.
 */
@TestSchema(
    """
    union Product = Toy | Fruit

    type Category {
      id: Int!
      "Use ctx.selections() to check requested types; return [Toy(id=category.id, prodType=\"Toy\"), Fruit(id=category.id, prodType=\"Fruit\")] for each requested union member"
      products: [Product] @resolver(isSelective: true)
    }

    extend type Query {
      "Return Category with id=<id argument>"
      category(id: Int!): Category @resolver
    }

    type Toy {
      id: Int!
      prodType: String
    }

    type Fruit {
      id: Int!
      prodType: String
    }

    type Shelf {
      "Return a Product (Toy with id=1, prodType=\"action_figure\")"
      topProduct: Product @resolver
      "Use objectValueFragment to access topProduct; cast to concrete type and return \"Toy: <prodType>\""
      topProductDescription: String @resolver
    }

    extend type Query {
      "Return a Shelf object"
      shelf: Shelf @resolver
    }

    type OwnedSelectionPayload {
      local: String
      delegated: String @resolver
      child(limit: Int): OwnedSelectionChild
      contact: OwnedSelectionContact
    }

    type OwnedSelectionChild {
      kept: String
      delegated: String @resolver
    }

    interface OwnedSelectionContact {
      label: String
    }

    type OwnedSelectionLocalContact implements OwnedSelectionContact {
      label: String
    }

    type OwnedSelectionNode implements Node @resolver(isSelective: true) {
      id: ID!
      local: String
      delegated: String @resolver
    }

    extend type Query {
      ownedSelectionPayload: OwnedSelectionPayload @resolver(isSelective: true)
      ownedSelectionNode: OwnedSelectionNode! @resolver
      ownedSelectionScalar: String @resolver(isSelective: true)
    }
"""
)
abstract class ReflectionContractTest : KotlinFeatureAppTestContractBase() {
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
    fun `engine-backed abstract getter dereferences concrete fields`() {
        execute(
            query = """
                query {
                    shelf {
                        topProductDescription
                    }
                }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                "shelf" to {
                    "topProductDescription" to "Toy: action_figure"
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
