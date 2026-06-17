package viaduct.java.runtime.bridge

import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.CoroutineScope
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
import viaduct.errors.handleTenantErrorsSuspend
import viaduct.errors.resultOfSuspend
import viaduct.java.api.context.NodeExecutionContext
import viaduct.java.api.internal.ObjectBase
import viaduct.java.api.internal.ResolverClassFinder
import viaduct.java.api.resolvers.FieldValue

/**
 * Kotlin bridge that wraps a batch Java node resolver and implements [NodeResolverExecutor].
 *
 * Called when [isBatching] is true. Receives all selectors in one call, creates per-selector
 * contexts, invokes the tenant's `batchResolve(List<Context>): CompletableFuture<List<FieldValue<T>>>`,
 * and zips results back to selectors in order.
 *
 * The return type mirrors the Kotlin tenant API
 * ([viaduct.tenant.runtime.execution.NodeBatchResolverExecutorImpl]): an ordered list of
 * [FieldValue] entries (one per selector), where each entry is either a successful value or an
 * error. Per-element errors surface to the engine as failed [Result]s without aborting the entire
 * batch.
 */
class NodeBatchResolverExecutorImpl(
    private val batchResolveFunction: (List<NodeExecutionContext<*>>) -> CompletableFuture<*>,
    override val typeName: String,
    private val resolverName: String,
    override val isSelective: Boolean = false,
    private val graphqlSchema: graphql.schema.GraphQLSchema? = null,
    private val classFinder: ResolverClassFinder? = null,
) : NodeResolverExecutor {
    override val metadata: ResolverMetadata = ResolverMetadata.forModern(resolverName, ResolverType.NODE)
    override val isBatching: Boolean = true

    override suspend fun resolve(
        selectors: List<NodeResolverExecutor.Selector>,
        context: EngineExecutionContext,
    ): Map<NodeResolverExecutor.Selector, Result<EngineObjectData>> {
        val scope = CoroutineScope(currentCoroutineContext())
        val javaContexts: List<NodeExecutionContext<*>> = selectors.map { selector ->
            SimpleNodeExecutionContext(
                serializedId = selector.id,
                typeName = typeName,
                requestContext = context.requestContext,
                engineExecutionContext = context,
                coroutineScope = scope,
                classFinder = classFinder,
            )
        }

        val rawResult: Any? = handleTenantErrorsSuspend(typeName) {
            batchResolveFunction(javaContexts).await()
        }

        if (rawResult !is List<*>) {
            throw TenantUsageException(
                "batchResolve for node $typeName must return CompletableFuture<List<FieldValue<T>>>, " +
                    "got ${rawResult?.javaClass?.name}"
            )
        }

        if (rawResult.size != selectors.size) {
            throw TenantUsageException(
                "batchResolve for node $typeName was given ${selectors.size} contexts but returned ${rawResult.size} entries"
            )
        }

        return selectors.zip(rawResult.map { unwrap(it) }).toMap()
    }

    private suspend fun unwrap(fieldValue: Any?): Result<EngineObjectData> {
        if (fieldValue !is FieldValue<*>) {
            return Result.failure(
                TenantResolverException(
                    TenantUsageException(
                        "batchResolve for node $typeName returned an entry that is not a FieldValue: " +
                            "${fieldValue?.javaClass?.name}"
                    ),
                    typeName
                )
            )
        }
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
