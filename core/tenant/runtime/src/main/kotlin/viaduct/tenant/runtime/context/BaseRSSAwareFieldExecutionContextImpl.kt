package viaduct.tenant.runtime.context

import kotlin.reflect.KClass
import viaduct.api.context.FieldExecutionContext
import viaduct.api.internal.InternalContext
import viaduct.api.select.SelectionSet
import viaduct.api.types.Arguments
import viaduct.api.types.CompositeOutput
import viaduct.api.types.Object
import viaduct.api.types.Query
import viaduct.engine.api.EngineObjectData
import viaduct.errors.FrameworkException
import viaduct.errors.handleFrameworkErrorsSuspend
import viaduct.tenant.runtime.toObjectGRT

/** Shared required selection set access for ordinary and connection field contexts. */
sealed class BaseRSSAwareFieldExecutionContextImpl<Q : Query, A : Arguments, R : CompositeOutput>(
    baseData: InternalContext,
    engineExecutionContextWrapper: EngineExecutionContextWrapper,
    selections: SelectionSet<R>,
    requestContext: Any?,
    arguments: A,
    private val syncObjectValueGetter: (suspend () -> EngineObjectData.Sync)?,
    private val syncQueryValueGetter: (suspend () -> EngineObjectData.Sync)?,
    private val objectCls: KClass<Object>,
    private val queryCls: KClass<Q>,
    ownedSelections: Lazy<SelectionSet<R>>,
) : FieldExecutionContext<Object, Q, A, R>,
    BaseFieldExecutionContextImpl<Q, A, R>(
        baseData,
        engineExecutionContextWrapper,
        selections,
        requestContext,
        arguments,
        ownedSelections,
    ) {
    /**
     * Resolves and returns the parent object value for this field.
     *
     * @throws FrameworkException if the sync object data is unavailable, which indicates
     *   an internal Viaduct error rather than a resolver bug.
     */
    override suspend fun getObjectValue(): Object =
        handleFrameworkErrorsSuspend("getObjectValue") {
            val resolvedSyncObjectValue = syncObjectValueGetter?.invoke()
                ?: throw FrameworkException(
                    "Sync object value is not available. " +
                        "This may indicate an internal error in Viaduct."
                )
            resolvedSyncObjectValue.toObjectGRT(this, objectCls)
        }

    override suspend fun getQueryValue(): Q =
        handleFrameworkErrorsSuspend("getQueryValue") {
            val resolvedSyncQueryValue = syncQueryValueGetter?.invoke()
                ?: throw FrameworkException(
                    "Sync query value is not available. " +
                        "This may indicate an internal error in Viaduct."
                )
            resolvedSyncQueryValue.toObjectGRT(this, queryCls)
        }
}
