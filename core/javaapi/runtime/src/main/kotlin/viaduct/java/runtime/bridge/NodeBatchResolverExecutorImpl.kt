package viaduct.java.runtime.bridge

import graphql.language.FragmentDefinition
import javax.inject.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.future.await
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.ResolverMetadata
import viaduct.engine.api.ResolverType
import viaduct.engine.api.invocationContextFor
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.errors.ErroneousFieldException
import viaduct.errors.FrameworkException
import viaduct.errors.PassthroughException
import viaduct.errors.TenantResolverException
import viaduct.errors.TenantUsageException
import viaduct.errors.resultOfSuspend
import viaduct.java.api.context.NodeExecutionContext
import viaduct.java.api.internal.BaseBatchedNodeResolver
import viaduct.java.api.internal.ObjectBase
import viaduct.java.api.resolvers.FieldValue
import viaduct.java.api.types.NodeObject
import viaduct.tenant.runtime.jvm.NodeBatchContext
import viaduct.tenant.runtime.jvm.executeNodeBatch

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
        return executeNodeBatch(
            selectors = selectors,
            typeName = typeName,
            inputFor = { selector ->
                val invocationContext = context.invocationContextFor(selector)
                NodeBatchContext<NodeExecutionContext<*>>(
                    context = SimpleNodeExecutionContext(
                        serializedId = selector.id,
                        typeName = typeName,
                        requestContext = invocationContext.requestContext,
                        engineExecutionContext = invocationContext,
                        coroutineScope = scope,
                        grtPackagePrefix = grtPackagePrefix,
                        knownFragments = knownFragments,
                    ),
                    internalID = invocationContext.globalIDCodec.deserialize(selector.id).localID,
                )
            },
            invoke = { contexts -> resolver.invokeNodeBatchResolver(contexts).await() },
            unwrap = { selector, fieldValue ->
                unwrap(fieldValue, context.invocationContextFor(selector))
            },
        )
    }

    private suspend fun unwrap(
        fieldValue: FieldValue<*>,
        context: EngineExecutionContext
    ): Result<EngineObjectData> {
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
                    "NodeReference returned from node resolver. Use a GRT builder instead of ctx.ref to construct your node object."
                )
            }
            convertResult(raw, graphqlSchema, context.globalIDCodec) as? EngineObjectData
                ?: throw FrameworkException(
                    "Node batch resolver for $typeName failed to convert result to EngineObjectData: ${raw.javaClass.name}"
                )
        }
    }
}
