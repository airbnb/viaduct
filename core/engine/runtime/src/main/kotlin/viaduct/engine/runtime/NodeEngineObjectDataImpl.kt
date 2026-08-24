package viaduct.engine.runtime

import graphql.schema.GraphQLObjectType
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import viaduct.engine.api.Caller
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.NodeEngineObjectData
import viaduct.engine.api.NodeReference
import viaduct.engine.runtime.EngineExecutionContextExtensions.copy

class NodeEngineObjectDataImpl(
    override val id: String,
    override val type: GraphQLObjectType,
    private val dispatcherRegistry: DispatcherRegistry,
    private val caller: Caller? = null,
) : NodeEngineObjectData, NodeReference, LazyEngineObjectData {
    private val resolutionStarted = AtomicBoolean(false)
    private val state = AtomicReference<State>(State.Pending)
    private val initialStateReady = CompletableDeferred<Unit>()

    override suspend fun fetch(selection: String): Any? {
        if (selection == "id") return id
        val data = materializedData()
        val source = data.firstOrNull { selection in it.fetchSelections() }
        return (source ?: data.first()).fetch(selection)
    }

    override suspend fun fetchOrNull(selection: String): Any? {
        if (selection == "id") return id
        val source = materializedData().firstOrNull { selection in it.fetchSelections() }
        return source?.fetchOrNull(selection)
    }

    override suspend fun fetchSelections(): Iterable<String> =
        buildSet {
            materializedData().forEach { addAll(it.fetchSelections()) }
        }

    override suspend fun resolveData(
        selections: EngineSelectionSet,
        context: EngineExecutionContext
    ): EngineObjectData {
        val isFirstResolution = resolutionStarted.compareAndSet(false, true)
        try {
            val nodeResolver = checkNotNull(dispatcherRegistry.getNodeResolverDispatcher(type.name)) {
                "No node resolver found for type ${type.name}"
            }
            val fieldScope = context.fieldScope
            val resolverContext = context.copy(
                fieldScopeSupplier = {
                    EngineExecutionContextImpl.FieldExecutionScopeImpl(
                        fragments = fieldScope.fragments,
                        variables = fieldScope.variables,
                        resolutionPolicy = fieldScope.resolutionPolicy,
                        attribution = fieldScope.attribution,
                        caller = caller,
                    )
                },
                currentResolver = Caller(
                    tenantName = nodeResolver.resolverMetadata.tenantMetadata?.name,
                    typeName = type.name,
                    fieldName = null,
                ),
            )
            return resolveLazyData(
                nodeResolver.resolve(id, selections, resolverContext),
                selections,
                resolverContext,
            ).also(::recordMaterialization)
        } catch (e: Exception) {
            if (isFirstResolution) {
                recordInitialFailure(e)
            }
            throw e
        }
    }

    private suspend fun resolveLazyData(
        data: EngineObjectData,
        selections: EngineSelectionSet,
        context: EngineExecutionContext,
    ): EngineObjectData {
        val seen = Collections.newSetFromMap(IdentityHashMap<EngineObjectData, Boolean>())
        seen += this

        var current = data
        while (current is LazyEngineObjectData) {
            check(seen.add(current)) {
                "Node resolver for ${type.name}($id) returned a cycle of lazy object references"
            }
            current = checkNotNull(current.resolveData(selections, context)) {
                "Node resolver for ${type.name}($id) returned a null lazy object reference"
            }
        }
        return current
    }

    private suspend fun materializedData(): List<EngineObjectData> {
        while (true) {
            when (val state = state.get()) {
                State.Pending -> initialStateReady.await()
                is State.Failed -> throw state.error
                is State.Available -> return state.data
            }
        }
    }

    private fun recordMaterialization(data: EngineObjectData) {
        state.updateAndGet { state ->
            State.Available(
                when (state) {
                    State.Pending,
                    is State.Failed -> listOf(data)
                    is State.Available -> state.data + data
                }
            )
        }
        initialStateReady.complete(Unit)
    }

    private fun recordInitialFailure(error: Exception) {
        if (state.compareAndSet(State.Pending, State.Failed(error))) {
            initialStateReady.complete(Unit)
        }
    }

    private sealed interface State {
        data object Pending : State

        data class Failed(val error: Exception) : State

        /** Completed resolver results in the order they became available. */
        data class Available(val data: List<EngineObjectData>) : State
    }
}
