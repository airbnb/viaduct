package viaduct.tenant.runtime.execution

import javax.inject.Provider
import kotlin.reflect.KFunction
import viaduct.api.FieldValue
import viaduct.api.NodeResolverBase
import viaduct.api.internal.ReflectionLoader
import viaduct.apiannotations.Attribution
import viaduct.apiannotations.AttributionContext
import viaduct.apiannotations.InternalApi
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.ResolverMetadata
import viaduct.engine.api.ResolverType
import viaduct.engine.api.TenantModuleMetadata
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.errors.ErroneousFieldException
import viaduct.errors.FrameworkException
import viaduct.errors.PassthroughException
import viaduct.errors.TenantResolverException
import viaduct.errors.TenantUsageException
import viaduct.errors.handleTenantErrorsSuspend
import viaduct.errors.resultOfSuspend
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
            throw FrameworkException("Unexpected return value from batchResolve function for node $typeName: $results")
        }
        if (selectors.size != results.size) {
            throw TenantResolverException(
                TenantUsageException(
                    "The batchResolve function in the Node resolver for $typeName was given a batch of size ${selectors.size} but returned ${results.size} elements"
                ),
                typeName
            )
        }
        return selectors.zip(results.map { unwrap(it) }).toMap()
    }

    @OptIn(InternalApi::class)
    private suspend fun unwrap(fieldValue: Any?): Result<EngineObjectData> {
        if (fieldValue !is FieldValue<*>) {
            return Result.failure(
                TenantResolverException(
                    TenantUsageException("Unexpected result type that is not a FieldValue: $fieldValue"),
                    typeName
                )
            )
        }

        // TODO: the pass through here for `ErroneousFieldException` is not our long-term
        // solution. Instead, we need a mechanism for passing field-level error information
        // from tenant exceptions into the final graphql field-error. See the
        // "GraphQL Error Message Shaping" discussion in
        // https://slate.sandcastle.musta.ch/I3TZD5c0dg; a solution for that would be a
        // better solution for `ErroneousFieldException`.
        return resultOfSuspend(
            mapException = { e ->
                if (e is PassthroughException || e is ErroneousFieldException) {
                    e
                } else {
                    TenantResolverException(e, typeName)
                }
            }
        ) {
            unwrapFieldValue(fieldValue)
        }
    }

    @Attribution(AttributionContext.TENANT)
    private fun unwrapFieldValue(fieldValue: FieldValue<*>): EngineObjectData = NodeUnbatchedResolverExecutorImpl.unwrapNodeResolverResult(fieldValue.get())
}
