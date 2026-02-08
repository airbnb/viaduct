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
import viaduct.tenant.runtime.toObjectGRT

/**
 * Implementation of [FieldExecutionContext] for non-mutation field resolvers.
 *
 * This class extends [BaseFieldExecutionContextImpl] to add object value access,
 * providing both lazy access via [objectValue] and synchronous access via [getObjectValue].
 *
 * The [getObjectValue] method returns a synchronously-accessible version of the object value
 * where all selections declared in the resolver's `objectValueFragment` have been eagerly resolved.
 * This is useful when you need to ensure all object data is available before proceeding,
 * or when passing data to non-suspending code.
 *
 * @param syncObjectValueGetter A suspending function that returns the synchronous object value,
 *        or null if no object selections were declared by the resolver
 */
class FieldExecutionContextImpl<Q : Query>(
    baseData: InternalContext,
    engineExecutionContextWrapper: EngineExecutionContextWrapper,
    selections: SelectionSet<CompositeOutput>,
    requestContext: Any?,
    arguments: Arguments,
    override val objectValue: Object,
    queryValue: Q,
    private val syncObjectValueGetter: (suspend () -> EngineObjectData.Sync)?,
    syncQueryValueGetter: (suspend () -> EngineObjectData.Sync)?,
    private val objectCls: KClass<Object>,
    queryCls: KClass<Q>,
) : FieldExecutionContext<Object, Q, Arguments, CompositeOutput>,
    BaseFieldExecutionContextImpl<Q>(
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
