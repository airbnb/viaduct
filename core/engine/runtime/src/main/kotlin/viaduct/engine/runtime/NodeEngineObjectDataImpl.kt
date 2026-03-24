package viaduct.engine.runtime

import graphql.schema.GraphQLObjectType
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.NodeEngineObjectData
import viaduct.engine.api.NodeReference

class NodeEngineObjectDataImpl(
    override val id: String,
    override val type: GraphQLObjectType,
    private val dispatcherRegistry: DispatcherRegistry
) : NodeEngineObjectData, NodeReference, LazyEngineObjectData {
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
        selections: EngineSelectionSet,
        context: EngineExecutionContext
    ): Boolean {
        if (!resolveDataCalled.compareAndSet(false, true)) {
            return false
        }

        try {
            val nodeResolver = dispatcherRegistry.getNodeResolverDispatcher(type.name)
                ?: throw IllegalStateException("No node resolver found for type ${type.name}")

            resolvedEngineObjectData = nodeResolver.resolve(id, selections, context)
            resolving.complete(Unit)
            return true
        } catch (e: Exception) {
            // don't consider real CancellationException as failures. Just rethrow
            if (e is CancellationException) currentCoroutineContext().ensureActive()

            resolving.completeExceptionally(e)
            throw e
        }
    }
}
