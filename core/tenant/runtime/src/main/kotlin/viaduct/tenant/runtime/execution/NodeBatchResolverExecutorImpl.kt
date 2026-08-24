package viaduct.tenant.runtime.execution

import javax.inject.Provider
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.engine.runtime.invocationContextFor
import viaduct.errors.ErroneousFieldException
import viaduct.errors.PassthroughException
import viaduct.errors.TenantResolverException
import viaduct.errors.TenantUsageException
import viaduct.errors.handleTenantErrorsResultSuspend
import viaduct.errors.resultOfSuspend
import viaduct.tenant.runtime.context.factory.NodeExecutionContextFactory
import viaduct.tenant.runtime.support.partitionByUniqueKey

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
    ): Map<NodeResolverExecutor.Selector, Result<EngineObjectData>> {
        val inputs = selectors.map { selector ->
            val invocationContext = context.invocationContextFor(selector)
            ResolverInput(
                selector = selector,
                context = factory(
                    invocationContext,
                    selector.selections,
                    invocationContext.requestContext,
                    selector.id,
                ),
                internalID = invocationContext.globalIDCodec.deserialize(selector.id).localID,
            )
        }
        val resolvedGroups = coroutineScope {
            // A request can contain the same decoded ID with different selections. Partition
            // into stable groups with unique IDs so every context reaches the resolver without
            // duplicate IDs in one invocation; the groups are then executed concurrently.
            partitionByUniqueKey(inputs) { it.internalID }
                .map { group -> async { resolveGroup(resolver, group) } }
                .awaitAll()
        }

        return linkedMapOf<NodeResolverExecutor.Selector, Result<EngineObjectData>>().apply {
            resolvedGroups.forEach { putAll(it) }
        }
    }

    private suspend fun resolveGroup(
        resolver: BaseBatchedNodeResolver,
        group: List<ResolverInput>,
    ): Map<NodeResolverExecutor.Selector, Result<EngineObjectData>> =
        handleTenantErrorsResultSuspend(typeName) {
            val contexts = group.map { it.context }
            val results: Map<NodeExecutionContext<*>, FieldValue<*>> =
                resolver.invokeNodeBatchResolver(contexts)
            if (contexts.size != results.size) {
                throw TenantResolverException(
                    TenantUsageException(
                        "The batchResolve function in the Node resolver for $typeName was given a batch of size ${contexts.size} but returned ${results.size} elements"
                    ),
                    typeName,
                )
            }
            val contextToSelector = group.associate { it.context to it.selector }
            val resolved = linkedMapOf<NodeResolverExecutor.Selector, Result<EngineObjectData>>()

            results.forEach { (returnedContext, fieldValue) ->
                val selector = contextToSelector[returnedContext]
                    ?: throw TenantResolverException(
                        TenantUsageException(
                            "The batchResolve function in the Node resolver for $typeName returned a context that was not in the input context list: $returnedContext"
                        ),
                        typeName,
                    )
                resolved[selector] = unwrap(fieldValue)
            }

            resolved
        }.getOrElse { failure ->
            group.associate { input ->
                input.selector to Result.failure(failure)
            }
        }

    private data class ResolverInput(
        val selector: NodeResolverExecutor.Selector,
        val context: NodeExecutionContext<*>,
        val internalID: String,
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
