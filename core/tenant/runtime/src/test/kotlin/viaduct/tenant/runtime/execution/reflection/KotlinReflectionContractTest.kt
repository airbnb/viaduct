@file:Suppress("unused", "ClassName")

package viaduct.tenant.runtime.execution.reflection

import viaduct.api.resolver.Resolver
import viaduct.tenant.runtime.execution.reflection.resolverbases.CategoryResolvers
import viaduct.tenant.runtime.execution.reflection.resolverbases.QueryResolvers
import viaduct.tenant.runtime.execution.reflection.resolverbases.ShelfResolvers

class KotlinReflectionContractTest : ReflectionContractTest() {
    @Resolver
    class Query_CategoryResolver : QueryResolvers.Category() {
        override suspend fun resolve(ctx: Context) =
            Category.Builder(ctx).also { builder ->
                builder.put(Category.Fields.id.name, ctx.arguments.id)
            }.build()
    }

    @Resolver
    class Query_ShelfResolver : QueryResolvers.Shelf() {
        override suspend fun resolve(ctx: Context) = Shelf.Builder(ctx).build()
    }

    @Resolver
    class Shelf_TopProductResolver : ShelfResolvers.TopProduct() {
        override suspend fun resolve(ctx: Context): Product = Toy.Builder(ctx).id(1).prodType("action_figure").build()
    }

    @Resolver(
        """
        fragment _ on Shelf {
            topProduct {
                ... on Toy { id prodType }
                ... on Fruit { id prodType }
            }
        }
        """
    )
    class Shelf_TopProductDescriptionResolver : ShelfResolvers.TopProductDescription() {
        override suspend fun resolve(ctx: Context): String {
            val product = ctx.getObjectValue().getTopProduct()
            return when (product) {
                is Toy -> "Toy: ${product.getProdType()}"
                is Fruit -> "Fruit: ${product.getProdType()}"
                else -> "Unknown"
            }
        }
    }

    @Resolver
    class Category_ProductsResolver : CategoryResolvers.Products() {
        override suspend fun resolve(ctx: Context): List<Product> {
            val products = listOf<Product>(
                Toy.Builder(ctx).id(123).build(),
                Fruit.Builder(ctx).id(123).build()
            )
            return products.map { product ->
                if (ctx.selections().requestsType(Toy.Reflection) && product is Toy) {
                    Toy.Builder(ctx).id(product.getId()).prodType("Toy").build()
                } else if (ctx.selections().requestsType(Fruit.Reflection) && product is Fruit) {
                    Fruit.Builder(ctx).id(product.getId()).prodType("Fruit").build()
                } else {
                    product
                }
            }
        }
    }
}
