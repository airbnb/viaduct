package viaduct.tenant.runtime.execution

import javax.inject.Provider
import viaduct.api.FieldValue
import viaduct.api.context.NodeExecutionContext
import viaduct.api.internal.BaseBatchedNodeResolver
import viaduct.apiannotations.Attribution
import viaduct.apiannotations.AttributionContext
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.ResolverMetadata
import viaduct.engine.api.ResolverType
import viaduct.engine.api.TenantModuleMetadata
import viaduct.engine.api.invocationContextFor
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.errors.ErroneousFieldException
import viaduct.errors.PassthroughException
import viaduct.errors.TenantResolverException
import viaduct.errors.resultOfSuspend
import viaduct.tenant.runtime.context.factory.NodeExecutionContextFactory
import viaduct.tenant.runtime.jvm.NodeBatchContext
import viaduct.tenant.runtime.jvm.executeNodeBatch

class NodeBatchResolverExecutorImpl(
    val resolver: Provider<out BaseBatchedNodeResolver>,
    override val typeName: String,
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
    ): Map<NodeResolverExecutor.Selector, Result<EngineObjectData>> =
        resolve(
            resolver = resolver.get(),
            selectors = selectors,
            context = context,
        )

    private suspend fun resolve(
        resolver: BaseBatchedNodeResolver,
        selectors: List<NodeResolverExecutor.Selector>,
        context: EngineExecutionContext,
    ): Map<NodeResolverExecutor.Selector, Result<EngineObjectData>> =
        executeNodeBatch(
            selectors = selectors,
            typeName = typeName,
            inputFor = { selector ->
                val invocationContext = context.invocationContextFor(selector)
                NodeBatchContext<NodeExecutionContext<*>>(
                    context = factory(
                        invocationContext,
                        selector.selections,
                        invocationContext.requestContext,
                        selector.id,
                    ),
                    internalID = invocationContext.globalIDCodec.deserialize(selector.id).localID,
                )
            },
            invoke = { contexts -> resolver.invokeNodeBatchResolver(contexts) },
            unwrap = { _, fieldValue -> unwrap(fieldValue) },
        )

    private suspend fun unwrap(fieldValue: FieldValue<*>): Result<EngineObjectData> {
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
