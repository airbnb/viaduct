package viaduct.engine.runtime

import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineSelectionSet

/**
 * A [FieldResolverDispatcher] decorator that eagerly resolves sync object and query values,
 * then passes them as both objectValue/queryValue and through trivial sync getters to the delegate.
 *
 * This ensures sync value computation happens within the delegate's execution scope
 * (and thus within instrumentation boundaries when wrapped by an instrumented dispatcher).
 */
class SyncFieldResolverDispatcher(
    private val delegate: FieldResolverDispatcher
) : FieldResolverDispatcher by delegate {
    override suspend fun resolve(
        arguments: Map<String, Any?>,
        objectValue: EngineObjectData,
        queryValue: EngineObjectData,
        syncObjectValueGetter: suspend () -> EngineObjectData.Sync,
        syncQueryValueGetter: suspend () -> EngineObjectData.Sync,
        selections: EngineSelectionSet?,
        context: EngineExecutionContext
    ): Any? {
        val syncObjectValue = syncObjectValueGetter()
        val syncQueryValue = syncQueryValueGetter()
        return delegate.resolve(
            arguments,
            syncObjectValue,
            syncQueryValue,
            { syncObjectValue },
            { syncQueryValue },
            selections,
            context
        )
    }
}
