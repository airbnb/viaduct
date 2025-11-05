package viaduct.engine.runtime

import graphql.schema.GraphQLObjectType
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import viaduct.engine.api.CheckerExecutor
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.NodeEngineObjectData
import viaduct.engine.api.NodeReference
import viaduct.engine.api.NodeResolverDispatcherRegistry
import viaduct.engine.api.RawSelectionSet
import viaduct.engine.api.TypeCheckerDispatcherRegistry

class NodeEngineObjectDataImpl(
    override val id: String,
    override val graphQLObjectType: GraphQLObjectType,
    private val resolverRegistry: NodeResolverDispatcherRegistry,
    private val checkerRegistry: TypeCheckerDispatcherRegistry
) : NodeEngineObjectData, NodeReference {
    private lateinit var resolvedEngineObjectData: EngineObjectData
    private val resolving = CompletableDeferred<Unit>()
    private val resolveDataCalled = AtomicBoolean(false)

    override suspend fun fetch(selection: String): Any? = idOrWait(selection) ?: resolvedEngineObjectData.fetch(selection)

    override suspend fun fetchOrNull(selection: String): Any? = idOrWait(selection) ?: resolvedEngineObjectData.fetchOrNull(selection)

    override suspend fun fetchSelections(): Iterable<String> {
        resolving.await()
        return resolvedEngineObjectData.fetchSelections()
    }

    private suspend fun idOrWait(selection: String): Any? {
        if (selection == "id") {
            return id
        }
        resolving.await()
        return null
    }

    /**
     * To be called by the engine to resolve this node reference.
     *
     * @return true if the data was resolved by this call, false if it was already called previously
     */
    override suspend fun resolveData(
        selections: RawSelectionSet,
        context: EngineExecutionContext
    ): Boolean {
        if (!resolveDataCalled.compareAndSet(false, true)) {
            return false
        }

        try {
            val nodeResolver = resolverRegistry.getNodeResolverDispatcher(graphQLObjectType.name)
                ?: throw IllegalStateException("No node resolver found for type ${graphQLObjectType.name}")

            if (!(context as EngineExecutionContextImpl).executeAccessChecksInModstrat) {
                val nodeChecker = checkerRegistry.getTypeCheckerDispatcher(graphQLObjectType.name)
                if (nodeChecker == null) {
                    resolvedEngineObjectData = nodeResolver.resolve(id, selections, context)
                    resolving.complete(Unit)
                } else {
                    supervisorScope {
                        // Execute node level access check with no arguments and no selection sets currently.
                        val checkAsync = async {
                            nodeChecker.execute(
                                emptyMap(),
                                mapOf(
                                    "key" to CheckerProxyEngineObjectData(
                                        ObjectEngineResultImpl.newForType(graphQLObjectType),
                                        "missing from checker RSS"
                                    )
                                ),
                                context,
                                CheckerExecutor.CheckerType.TYPE
                            )
                        }
                        runCatching {
                            resolvedEngineObjectData = nodeResolver.resolve(id, selections, context)
                        }.onSuccess {
                            val checkerResult = checkAsync.await()
                            checkerResult.asError?.let { throw it.error }
                            resolving.complete(Unit)
                        }.getOrThrow()
                    }
                }
            } else {
                resolvedEngineObjectData = nodeResolver.resolve(id, selections, context)
                resolving.complete(Unit)
            }
            return true
        } catch (e: Exception) {
            resolving.completeExceptionally(e)
            throw e
        }
    }
}
