@file:Suppress("unused", "ClassName")
@file:OptIn(ExperimentalApi::class)

package viaduct.tenant.tutorial13

import org.junit.jupiter.api.Test
import viaduct.api.resolver.Resolver
import viaduct.apiannotations.ExperimentalApi
import viaduct.graphql.test.assertEquals
import viaduct.tenant.tutorial13.resolverbases.ProductFactoryResolvers
import viaduct.tenant.tutorial13.resolverbases.QueryResolvers

/**
 * NEXT: [viaduct.tenant.tutorial14.NamedFragmentsFeatureAppTest]
 */
class RootFieldRefFeatureAppTest : RootFieldRefContractTest() {
    @Resolver
    class productFactoryCreateResolver : ProductFactoryResolvers.Create() {
        override suspend fun resolve(ctx: Context): Product? {
            return Product.of(ctx) {
                name("Widget")
                price(42)
                related(
                    ctx.ref(
                        ProductFactory.createWithArguments {
                            name("Related widget")
                            metadata(mapOf("source" to "catalog", "scores" to listOf(1, 2)))
                            spec(ProductSpecInput.Builder(ctx).quantity(3).build())
                            kind(ProductKind.PHYSICAL)
                            tags(listOf("featured", "new"))
                            ownerId(ctx.globalIDFor(Owner.Reflection, "owner-1"))
                        }
                    )
                )
            }
        }
    }

    @Resolver
    class productFactoryCreateWithArgumentsResolver : ProductFactoryResolvers.CreateWithArguments() {
        override suspend fun resolve(ctx: Context): Product? {
            return Product.of(ctx) {
                name(ctx.arguments.name)
                price(ctx.arguments.spec.quantity)
                metadata(ctx.arguments.metadata)
            }
        }
    }

    @Resolver
    class productResolver : QueryResolvers.Product() {
        override suspend fun resolve(ctx: Context): Product? {
            return ctx.ref(ProductFactory.create())
        }
    }

    @Test
    fun `generated root field reference resolves through namespace types`() {
        execute(
            query = """{ product { name price } }"""
        ).assertEquals {
            "data" to {
                "product" to {
                    "name" to "Widget"
                    "price" to 42
                }
            }
        }
    }

    @Test
    fun `generated root field reference forwards arguments`() {
        execute(
            query = """{ product { related { name price metadata } } }"""
        ).assertEquals {
            "data" to {
                "product" to {
                    "related" to {
                        "name" to "Related widget"
                        "price" to 3
                        "metadata" to {
                            "source" to "catalog"
                            "scores" to listOf(1, 2)
                        }
                    }
                }
            }
        }
    }
}
