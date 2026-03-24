@file:Suppress("unused", "ClassName")

package viaduct.tenant.runtime.execution.reflection

import kotlin.collections.listOf
import org.junit.jupiter.api.Test
import viaduct.api.Resolver
import viaduct.graphql.test.assertEquals
import viaduct.tenant.runtime.execution.reflection.resolverbases.CategoryResolvers
import viaduct.tenant.runtime.execution.reflection.resolverbases.QueryResolvers
import viaduct.tenant.runtime.fixtures.FeatureAppTestBase

class ReflectionFeatureAppTest : FeatureAppTestBase() {
    override var sdl = """
        | #START_SCHEMA
        | union Product = Toy | Fruit
        |
        | type Category {
        |   id: Int!
        |   products: [Product] @resolver
        | }
        |
        | extend type Query {
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

    @Resolver
    class Query_CategoryResolver : QueryResolvers.Category() {
        override suspend fun resolve(ctx: Context) =
            Category.Builder(ctx).also { builder ->
                if (ctx.selections().contains(Category.Reflection.Fields.id)) {
                    builder.put(Category.Reflection.Fields.id.name, ctx.arguments.id)
                }
            }.build()
    }

    @Resolver
    class Category_ProductsResolver : CategoryResolvers.Products() {
        override suspend fun resolve(ctx: Context): List<Product> {
            val products = listOf<Product>(
                Toy.Builder(ctx)
                    .id(123)
                    .build(),
                Fruit.Builder(ctx)
                    .id(123)
                    .build()
            )
            // Fake find and build of products using reflection
            return products.map { product ->
                if (ctx.selections().requestsType(Toy.Reflection) && product is Toy) {
                    Toy.Builder(ctx)
                        .id(product.getId())
                        .prodType("Toy")
                        .build()
                } else if (ctx.selections().requestsType(Fruit.Reflection) && product is Fruit) {
                    Fruit.Builder(ctx)
                        .id(product.getId())
                        .prodType("Fruit")
                        .build()
                } else {
                    product
                }
            }
        }
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
