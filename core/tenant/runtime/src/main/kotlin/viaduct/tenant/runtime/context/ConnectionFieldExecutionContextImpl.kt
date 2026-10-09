package viaduct.tenant.runtime.context

import kotlin.reflect.KClass
import viaduct.api.context.ConnectionFieldExecutionContext
import viaduct.api.context.ResolverOwnedSelectionsContext
import viaduct.api.context.SelectiveFieldExecutionContext
import viaduct.api.internal.InternalContext
import viaduct.api.select.SelectionSet
import viaduct.api.types.Connection
import viaduct.api.types.ConnectionArguments
import viaduct.api.types.Object
import viaduct.api.types.Query
import viaduct.engine.api.EngineObjectData

/**
 * Runtime implementation of [ConnectionFieldExecutionContext].
 *
 * Wires together the engine's execution data with the typed [ConnectionArguments] so that
 * connection resolvers can access pagination arguments and the parent object value.
 * Constructed by the Viaduct runtime; not instantiated directly by resolver code.
 */
class ConnectionFieldExecutionContextImpl<Q : Query>(
    baseData: InternalContext,
    engineExecutionContextWrapper: EngineExecutionContextWrapper,
    selections: SelectionSet<Connection<*, *>>,
    requestContext: Any?,
    arguments: ConnectionArguments,
    syncObjectValueGetter: (suspend () -> EngineObjectData.Sync)?,
    syncQueryValueGetter: (suspend () -> EngineObjectData.Sync)?,
    objectCls: KClass<Object>,
    queryCls: KClass<Q>,
    ownedSelections: Lazy<SelectionSet<Connection<*, *>>> = lazyOf(selections),
) : ConnectionFieldExecutionContext<Object, Q, ConnectionArguments, Connection<*, *>>,
    SelectiveFieldExecutionContext<Connection<*, *>>,
    ResolverOwnedSelectionsContext<Connection<*, *>>,
    BaseRSSAwareFieldExecutionContextImpl<Q, ConnectionArguments, Connection<*, *>>(
        baseData,
        engineExecutionContextWrapper,
        selections,
        requestContext,
        arguments,
        syncObjectValueGetter,
        syncQueryValueGetter,
        objectCls,
        queryCls,
        ownedSelections,
    ) {
    override fun selections(): SelectionSet<Connection<*, *>> = selectionSet()

    override fun ownedSelections(): SelectionSet<Connection<*, *>> = ownedSelectionSet()
}
