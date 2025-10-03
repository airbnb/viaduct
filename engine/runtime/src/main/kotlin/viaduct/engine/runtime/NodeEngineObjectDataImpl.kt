package viaduct.engine.runtime

import graphql.schema.GraphQLObjectType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.NodeEngineObjectData
import viaduct.engine.api.NodeResolverDispatcherRegistry
import viaduct.engine.api.RawSelectionSet
import viaduct.engine.api.TypeCheckerDispatcherRegistry

class NodeEngineObjectDataImpl(
    override val id: String,
    override val graphQLObjectType: GraphQLObjectType,
    private val resolverRegistry: NodeResolverDispatcherRegistry,
    private val checkerRegistry: TypeCheckerDispatcherRegistry
) : NodeEngineObjectData {
    private lateinit var resolvedEngineObjectData: EngineObjectData
    private val resolving = CompletableDeferred<Unit>()

    /**
     * Fetch a field from this node reference.
     * This method suspends until the engine resolves this node reference.
     * It returns null if there is an exception during engine resolution.
     */
    override suspend fun fetch(selection: String): Any? {
        // Return the ID immediately if the requested field is "id"
        if (selection == "id") {
            return id
        }

        // Wait for the engine to resolve this node reference
        resolving.await()
        return resolvedEngineObjectData.fetch(selection)
    }

    /**
     * To be called by the engine to resolve this node reference.
     */
    override suspend fun resolveData(
        selections: RawSelectionSet,
        context: EngineExecutionContext
    ) {
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
                                context
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
        } catch (e: Exception) {
            resolving.completeExceptionally(e)
            throw e
        }
    }
}
