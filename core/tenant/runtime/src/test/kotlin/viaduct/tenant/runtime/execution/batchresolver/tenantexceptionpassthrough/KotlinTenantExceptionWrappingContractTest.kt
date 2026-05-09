@file:Suppress("unused", "ClassName")

package viaduct.tenant.runtime.execution.batchresolver.tenantexceptionpassthrough

import viaduct.api.FieldValue
import viaduct.api.resolver.Resolver
import viaduct.errors.ErroneousFieldException
import viaduct.errors.FieldError
import viaduct.errors.TenantUsageException
import viaduct.tenant.runtime.execution.batchresolver.tenantexceptionpassthrough.resolverbases.NodeResolvers
import viaduct.tenant.runtime.execution.batchresolver.tenantexceptionpassthrough.resolverbases.QueryResolvers

class KotlinTenantExceptionWrappingContractTest : TenantExceptionWrappingContractTest() {
    override fun setNodeBatchShouldReturnTenantException(enabled: Boolean) {
        ItemResolver.shouldReturnTenantException = enabled
    }

    override fun setNodeBatchShouldReturnErroneousFieldException(enabled: Boolean) {
        ItemResolver.shouldReturnErroneousFieldException = enabled
    }

    @Resolver
    class Query_ItemResolver : QueryResolvers.Item() {
        override suspend fun resolve(ctx: Context): Item = ctx.nodeRef(ctx.arguments.id)
    }

    @Resolver
    class ItemResolver : NodeResolvers.Item() {
        companion object {
            var shouldReturnTenantException = false
            var shouldReturnErroneousFieldException = false
        }

        override suspend fun batchResolve(contexts: List<Context>): List<FieldValue<Item>> =
            contexts.map { ctx ->
                when {
                    shouldReturnTenantException ->
                        FieldValue.ofError(TenantUsageException("node api misuse"))
                    shouldReturnErroneousFieldException ->
                        FieldValue.ofError(
                            ErroneousFieldException(
                                listOf(FieldError(message = "upstream error"))
                            )
                        )
                    else ->
                        FieldValue.ofValue(Item.Builder(ctx).id(ctx.id).build())
                }
            }
    }
}
