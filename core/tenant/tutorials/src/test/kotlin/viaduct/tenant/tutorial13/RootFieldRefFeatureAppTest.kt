@file:Suppress("unused", "ClassName")
@file:OptIn(ExperimentalApi::class)

package viaduct.tenant.tutorial13

import org.junit.jupiter.api.Test
import viaduct.api.Resolver
import viaduct.api.types.Arguments
import viaduct.apiannotations.ExperimentalApi
import viaduct.graphql.test.assertEquals
import viaduct.tenant.tutorial13.resolverbases.ProductFactoryResolvers
import viaduct.tenant.tutorial13.resolverbases.QueryResolvers

class RootFieldRefFeatureAppTest : RootFieldRefContractTest() {
    @Resolver
    class productFactoryCreateResolver : ProductFactoryResolvers.Create() {
        override suspend fun resolve(ctx: Context): Product? {
            return Product.of(ctx) {
                name("Widget")
                price(42)
            }
        }
    }

    @Resolver
    class productResolver : QueryResolvers.Product() {
        override suspend fun resolve(ctx: Context): Product? {
            return ctx.rootFieldRef(
                ProductFactory.Fields.create,
                Arguments.NoArguments
            )
        }
    }

    @Test
    fun `rootFieldRef resolves through namespace types`() {
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
}
