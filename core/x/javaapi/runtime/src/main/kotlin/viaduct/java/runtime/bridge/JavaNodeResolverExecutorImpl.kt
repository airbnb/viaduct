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
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.errors.FrameworkException
import viaduct.errors.TenantUsageException
import viaduct.errors.handleTenantErrorsSuspend
import viaduct.errors.resultOfSuspend
import viaduct.java.api.internal.BaseUnbatchedNodeResolver
import viaduct.java.api.internal.ObjectBase
import viaduct.java.api.internal.ResolverClassFinder

/**
 * Kotlin bridge that wraps an unbatched Java node resolver and implements [NodeResolverExecutor].
 *
 * Called for each node fetch individually ([isBatching] = false). Creates a [SimpleNodeExecutionContext]
 * from the selector's serialized GlobalID, invokes the tenant's `resolve(Context)` method, and
 * unwraps the Java GRT result back to [EngineObjectData].
 */
class JavaNodeResolverExecutorImpl(
    private val resolver: Provider<BaseUnbatchedNodeResolver>,
    override val typeName: String,
    private val resolverName: String,
    override val isSelective: Boolean = false,
    private val graphqlSchema: graphql.schema.GraphQLSchema? = null,
    private val classFinder: ResolverClassFinder? = null,
    private val knownFragments: Map<String, FragmentDefinition> = emptyMap(),
) : NodeResolverExecutor {
    override val metadata: ResolverMetadata = ResolverMetadata.forModern(resolverName, ResolverType.NODE)
    override val isBatching: Boolean = false

    override suspend fun resolve(
        selectors: List<NodeResolverExecutor.Selector>,
        context: EngineExecutionContext,
    ): Map<NodeResolverExecutor.Selector, Result<EngineObjectData>> {
        require(selectors.size == 1) {
            "Unbatched Java node resolver should only receive single selector, got ${selectors.size}"
        }

        val selector = selectors.first()
        val result = resultOfSuspend {
            resolveOne(selector = selector, context = context)
        }
        return mapOf(selector to result)
    }

    private suspend fun resolveOne(
        selector: NodeResolverExecutor.Selector,
        context: EngineExecutionContext,
    ): EngineObjectData {
        val scope = CoroutineScope(currentCoroutineContext())
        val javaContext = SimpleNodeExecutionContext(
            serializedId = selector.id,
            typeName = typeName,
            requestContext = context.requestContext,
            engineExecutionContext = context,
            coroutineScope = scope,
            classFinder = classFinder,
            knownFragments = knownFragments,
        )

        val result = handleTenantErrorsSuspend(typeName) {
            resolver.get().invokeNodeResolver(javaContext).await()
        }

        return unwrapNodeResult(result)
    }

    private fun unwrapNodeResult(result: Any?): EngineObjectData {
        if (result !is ObjectBase) {
            throw TenantUsageException("Unexpected result type that is not a GRT for a node object: $result")
        }
        if (result.javaNodeReference != null) {
            throw TenantUsageException(
                "NodeReference returned from node resolver. Use a GRT builder instead of ctx.nodeRef to construct your node object."
            )
        }
        return convertResult(result, graphqlSchema) as? EngineObjectData
            ?: throw FrameworkException(
                "Node resolver for $typeName failed to convert result to EngineObjectData: ${result.javaClass.name}"
            )
    }
}
