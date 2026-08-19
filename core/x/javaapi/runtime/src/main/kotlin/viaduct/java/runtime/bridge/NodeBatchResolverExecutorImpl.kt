package viaduct.java.runtime.bridge

import graphql.language.FragmentDefinition
import javax.inject.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.future.await
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.ResolverMetadata
import viaduct.engine.api.ResolverType
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.errors.ErroneousFieldException
import viaduct.errors.FrameworkException
import viaduct.errors.PassthroughException
import viaduct.errors.TenantResolverException
import viaduct.errors.TenantUsageException
import viaduct.errors.handleTenantErrorsResultSuspend
import viaduct.errors.resultOfSuspend
import viaduct.java.api.context.NodeExecutionContext
import viaduct.java.api.internal.BaseBatchedNodeResolver
import viaduct.java.api.internal.ObjectBase
import viaduct.java.api.resolvers.FieldValue
import viaduct.java.api.types.NodeObject
import viaduct.tenant.runtime.support.partitionByUniqueKey

/**
 * Kotlin bridge that wraps a batch Java node resolver and implements [NodeResolverExecutor].
 *
 * Called when [isBatching] is true. Receives all selectors in one call, creates per-selector
 * contexts, invokes the tenant's
 * `batchResolve(List<Context>): CompletableFuture<Map<Context, FieldValue<T>>>`, and binds results
 * back to selectors by context identity.
 *
 * The return type mirrors the Kotlin tenant API
 * ([viaduct.tenant.runtime.execution.NodeBatchResolverExecutorImpl]): a context-keyed map of
 * [FieldValue] entries. Per-element errors surface to the engine as failed [Result]s without
 * aborting the entire batch. Every input context must have a corresponding map entry.
 */
class NodeBatchResolverExecutorImpl(
    private val resolver: Provider<out BaseBatchedNodeResolver<*>>,
    override val typeName: String,
    private val resolverName: String,
    override val isSelective: Boolean = false,
    private val graphqlSchema: graphql.schema.GraphQLSchema? = null,
    private val grtPackagePrefix: String? = null,
    private val knownFragments: Map<String, FragmentDefinition> = emptyMap(),
) : NodeResolverExecutor {
    override val metadata: ResolverMetadata = ResolverMetadata.forModern(resolverName, ResolverType.NODE)
    override val isBatching: Boolean = true

    override suspend fun resolve(
        selectors: List<NodeResolverExecutor.Selector>,
        context: EngineExecutionContext,
    ): Map<NodeResolverExecutor.Selector, Result<EngineObjectData>> =
        resolve(
            resolver = resolver.get(),
            selectors = selectors,
            context = context,
        )

    private suspend fun <R : NodeObject> resolve(
        resolver: BaseBatchedNodeResolver<R>,
        selectors: List<NodeResolverExecutor.Selector>,
        context: EngineExecutionContext,
    ): Map<NodeResolverExecutor.Selector, Result<EngineObjectData>> {
        val scope = CoroutineScope(currentCoroutineContext())
        val inputs = selectors.map { selector ->
            ResolverInput(
                selector = selector,
                context = SimpleNodeExecutionContext(
                    serializedId = selector.id,
                    typeName = typeName,
                    requestContext = context.requestContext,
                    engineExecutionContext = context,
                    coroutineScope = scope,
                    grtPackagePrefix = grtPackagePrefix,
                    knownFragments = knownFragments,
                ),
                internalID = context.globalIDCodec.deserialize(selector.id).localID,
            )
        }
        val resolvedGroups = coroutineScope {
            partitionByUniqueKey(inputs) { it.internalID }
                .map { group -> async { resolveGroup(resolver, group) } }
                .awaitAll()
        }

        return linkedMapOf<NodeResolverExecutor.Selector, Result<EngineObjectData>>().apply {
            resolvedGroups.forEach { putAll(it) }
        }
    }

    private suspend fun <R : NodeObject> resolveGroup(
        resolver: BaseBatchedNodeResolver<R>,
        group: List<ResolverInput>,
    ): Map<NodeResolverExecutor.Selector, Result<EngineObjectData>> =
        handleTenantErrorsResultSuspend(typeName) {
            val javaContexts = group.map { it.context }
            val results: Map<NodeExecutionContext<*>, FieldValue<R>> =
                resolver.invokeNodeBatchResolver(javaContexts).await()
            if (javaContexts.size != results.size) {
                throw TenantUsageException(
                    "batchResolve for node $typeName was given ${javaContexts.size} contexts but returned ${results.size} entries"
                )
            }
            val contextToSelector = group.associate { it.context to it.selector }
            val resolved = linkedMapOf<NodeResolverExecutor.Selector, Result<EngineObjectData>>()

            results.forEach { (returnedContext, fieldValue) ->
                val selector = contextToSelector[returnedContext]
                    ?: throw TenantUsageException(
                        "batchResolve for node $typeName returned a context that was not in the input context list: $returnedContext"
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
        return resultOfSuspend(
            mapException = { e ->
                if (e is PassthroughException || e is ErroneousFieldException) {
                    e
                } else {
                    TenantResolverException(e, typeName)
                }
            }
        ) {
            val raw = fieldValue.get()
            if (raw !is ObjectBase) {
                throw TenantUsageException("Unexpected result type that is not a GRT for a node object: $raw")
            }
            if (raw.javaNodeReference != null) {
                throw TenantUsageException(
                    "NodeReference returned from node resolver. Use a GRT builder instead of ctx.nodeRef to construct your node object."
                )
            }
            convertResult(raw, graphqlSchema) as? EngineObjectData
                ?: throw FrameworkException(
                    "Node batch resolver for $typeName failed to convert result to EngineObjectData: ${raw.javaClass.name}"
                )
        }
    }
}
