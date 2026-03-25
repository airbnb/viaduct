package viaduct.tenant.runtime.context

import kotlin.reflect.KClass
import viaduct.api.context.ConnectionFieldExecutionContext
import viaduct.api.internal.InternalContext
import viaduct.api.select.SelectionSet
import viaduct.api.types.Connection
import viaduct.api.types.ConnectionArguments
import viaduct.api.types.Object
import viaduct.api.types.Query
import viaduct.apiannotations.ExperimentalApi
import viaduct.engine.api.EngineObjectData
import viaduct.tenant.runtime.toObjectGRT

/**
 * Runtime implementation of [ConnectionFieldExecutionContext].
 *
 * Wires together the engine's execution data with the typed [ConnectionArguments] so that
 * connection resolvers can access pagination arguments and the parent object value.
 * Constructed by the Viaduct runtime; not instantiated directly by resolver code.
 */
@ExperimentalApi
@Suppress("UNCHECKED_CAST")
class ConnectionFieldExecutionContextImpl<Q : Query>(
    baseData: InternalContext,
    engineExecutionContextWrapper: EngineExecutionContextWrapper,
    selections: SelectionSet<Connection<*, *>>,
    requestContext: Any?,
    arguments: ConnectionArguments,
    override val objectValue: Object,
    queryValue: Q,
    private val syncObjectValueGetter: (suspend () -> EngineObjectData.Sync)?,
    syncQueryValueGetter: (suspend () -> EngineObjectData.Sync)?,
    private val objectCls: KClass<Object>,
    queryCls: KClass<Q>,
) : ConnectionFieldExecutionContext<Object, Q, ConnectionArguments, Connection<*, *>>,
    BaseFieldExecutionContextImpl<Q, ConnectionArguments, Connection<*, *>>(
        baseData,
        engineExecutionContextWrapper,
        selections,
        requestContext,
        arguments,
        queryValue,
        syncQueryValueGetter,
        queryCls,
    ) {
    /**
     * Resolves and returns the parent object value for this connection field.
     *
     * @throws IllegalStateException if the sync object data is unavailable, which indicates
     *   an internal Viaduct error rather than a resolver bug.
     */
    override suspend fun getObjectValue(): Object {
        val resolvedSyncObjectValue = syncObjectValueGetter?.invoke()
            ?: throw IllegalStateException(
                "Sync object value is not available. " +
                    "This may indicate an internal error in Viaduct."
            )
        return resolvedSyncObjectValue.toObjectGRT(this, objectCls)
    }
}
