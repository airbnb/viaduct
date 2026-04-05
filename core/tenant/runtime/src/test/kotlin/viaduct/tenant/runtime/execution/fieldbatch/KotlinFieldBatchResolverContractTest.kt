@file:Suppress("unused", "ClassName")

package viaduct.tenant.runtime.execution.fieldbatch

import viaduct.api.FieldValue
import viaduct.api.Resolver
import viaduct.tenant.runtime.execution.fieldbatch.resolverbases.ItemResolvers
import viaduct.tenant.runtime.execution.fieldbatch.resolverbases.QueryResolvers

class KotlinFieldBatchResolverContractTest : FieldBatchResolverContractTest() {
    @Resolver
    class Query_ItemsResolver : QueryResolvers.Items() {
        override suspend fun resolve(ctx: Context): List<Item> {
            val count = ctx.arguments.count ?: 2
            return (1..count).map { i ->
                Item.Builder(ctx).id("item-$i").build()
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
                        Item.Builder(ctx).id("$itemId-list-$i-size-${contexts.size}").build()
                    }
                )
            }
        }
    }
}
