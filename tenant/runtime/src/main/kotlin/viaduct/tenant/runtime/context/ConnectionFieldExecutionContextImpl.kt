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
    override suspend fun getObjectValue(): Object {
        val resolvedSyncObjectValue = syncObjectValueGetter?.invoke()
            ?: throw IllegalStateException(
                "Sync object value is not available. " +
                    "This may indicate an internal error in Viaduct."
            )
        return resolvedSyncObjectValue.toObjectGRT(this, objectCls)
    }
}
