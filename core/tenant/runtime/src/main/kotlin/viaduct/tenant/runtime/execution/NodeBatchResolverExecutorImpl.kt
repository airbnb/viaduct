package viaduct.tenant.runtime.execution

import javax.inject.Provider
import kotlin.reflect.KFunction
import viaduct.api.FieldValue
import viaduct.api.NodeResolverBase
import viaduct.api.internal.ReflectionLoader
import viaduct.apiannotations.InTenantCode
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.ResolverMetadata
import viaduct.engine.api.ResolverType
import viaduct.engine.api.TenantModuleMetadata
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.errors.TenantUsageException
import viaduct.errors.handleTenantErrorsResultSuspend
import viaduct.errors.handleTenantErrorsSuspend
import viaduct.tenant.runtime.context.factory.NodeExecutionContextFactory

class NodeBatchResolverExecutorImpl(
    val resolver: Provider<out NodeResolverBase<*>>,
    private val batchResolveFunction: KFunction<*>,
    override val typeName: String,
    private val reflectionLoader: ReflectionLoader,
    private val factory: NodeExecutionContextFactory,
    private val resolverName: String,
    override val isSelective: Boolean,
    private val tenantMetadata: TenantModuleMetadata? = null,
) : NodeResolverExecutor {
    override val metadata = ResolverMetadata.forModern(resolverName, ResolverType.NODE, tenantMetadata)
    override val isBatching = true

    override suspend fun resolve(
        selectors: List<NodeResolverExecutor.Selector>,
        context: EngineExecutionContext
    ): Map<NodeResolverExecutor.Selector, Result<EngineObjectData>> {
        val contexts = selectors.map { key ->
            factory(context, key.selections, context.requestContext, key.id)
        }
        val resolver = resolver.get()
        val results: Any? = handleTenantErrorsSuspend(typeName) {
            callResolver(batchResolveFunction, resolver, contexts)
        }
        if (results !is List<*>) {
            throw TenantUsageException("Unexpected return value from batchResolve function for node $typeName: $results")
        }
        if (selectors.size != results.size) {
            throw TenantUsageException(
                "The batchResolve function in the Node resolver for $typeName was given a batch of size ${selectors.size} but returned ${results.size} elements"
            )
        }
        return selectors.zip(results.map { unwrap(it) }).toMap()
    }

    private suspend fun unwrap(fieldValue: Any?): Result<EngineObjectData> {
        if (fieldValue !is FieldValue<*>) {
            throw IllegalStateException("Unexpected result type that is not a FieldValue: $fieldValue")
        }

        return handleTenantErrorsResultSuspend(typeName) {
            unwrapFieldValue(fieldValue)
        }
    }

    @InTenantCode
    private suspend fun unwrapFieldValue(fieldValue: FieldValue<*>): EngineObjectData = NodeUnbatchedResolverExecutorImpl.unwrapNodeResolverResult(fieldValue.get())
}
