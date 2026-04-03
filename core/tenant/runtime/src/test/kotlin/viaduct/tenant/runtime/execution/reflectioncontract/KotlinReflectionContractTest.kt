@file:Suppress("unused", "ClassName")

package viaduct.tenant.runtime.execution.reflectioncontract

import viaduct.api.Resolver
import viaduct.tenant.runtime.execution.reflectioncontract.resolverbases.CategoryResolvers
import viaduct.tenant.runtime.execution.reflectioncontract.resolverbases.QueryResolvers
import viaduct.tenant.runtime.fixtures.ReflectionContractTest

class KotlinReflectionContractTest : ReflectionContractTest() {
    @Resolver
    class Query_CategoryResolver : QueryResolvers.Category() {
        override suspend fun resolve(ctx: Context) =
            Category.Builder(ctx).also { builder ->
                builder.put(Category.Fields.id.name, ctx.arguments.id)
            }.build()
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
