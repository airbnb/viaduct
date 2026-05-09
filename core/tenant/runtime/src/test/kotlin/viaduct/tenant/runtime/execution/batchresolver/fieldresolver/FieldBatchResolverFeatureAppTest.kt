@file:Suppress("unused", "ClassName")

package viaduct.tenant.runtime.execution.batchresolver.fieldresolver

import viaduct.api.FieldValue
import viaduct.api.resolver.Resolver
import viaduct.errors.TenantUsageException
import viaduct.tenant.runtime.execution.batchresolver.fieldresolver.resolverbases.ItemResolvers
import viaduct.tenant.runtime.execution.batchresolver.fieldresolver.resolverbases.QueryResolvers

class FieldBatchResolverFeatureAppTest : FieldBatchResolverContractTest() {
    override fun setBatchedFieldShouldReturnTenantException(enabled: Boolean) {
        Item_BatchedFieldResolver.shouldReturnTenantException = enabled
    }

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
        companion object {
            var shouldReturnTenantException = false
        }

        override suspend fun batchResolve(contexts: List<Context>): List<FieldValue<String>> {
            return contexts.map { ctx ->
                val itemId = ctx.getObjectValue().getId()
                if (shouldReturnTenantException) {
                    FieldValue.ofError(TenantUsageException("field api misuse"))
                } else {
                    FieldValue.ofValue("batched-$itemId-size-${contexts.size}")
                }
            }
        }
    }

    @Resolver(
        objectValueFragment = "fragment _ on Item { id }",
    )
    class Item_ListFieldResolver : ItemResolvers.ListField() {
        override suspend fun batchResolve(contexts: List<Context>): List<FieldValue<List<Item>>> {
            return contexts.map { ctx ->
                val itemId = ctx.getObjectValue().getId()
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
}
