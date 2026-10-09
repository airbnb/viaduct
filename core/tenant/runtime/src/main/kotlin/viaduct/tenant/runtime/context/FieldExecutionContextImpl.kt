package viaduct.tenant.runtime.context

import kotlin.reflect.KClass
import viaduct.api.context.FieldExecutionContext
import viaduct.api.context.ResolverOwnedSelectionsContext
import viaduct.api.context.SelectiveFieldExecutionContext
import viaduct.api.internal.InternalContext
import viaduct.api.select.SelectionSet
import viaduct.api.types.Arguments
import viaduct.api.types.CompositeOutput
import viaduct.api.types.Object
import viaduct.api.types.Query
import viaduct.engine.api.EngineObjectData

/**
 * Implementation of [FieldExecutionContext] for non-mutation field resolvers.
 *
 * This class extends [BaseRSSAwareFieldExecutionContextImpl] to add object value access via [getObjectValue].
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
    syncObjectValueGetter: (suspend () -> EngineObjectData.Sync)?,
    syncQueryValueGetter: (suspend () -> EngineObjectData.Sync)?,
    objectCls: KClass<Object>,
    queryCls: KClass<Q>,
    ownedSelections: Lazy<SelectionSet<CompositeOutput>> = lazyOf(selections),
) : FieldExecutionContext<Object, Q, Arguments, CompositeOutput>,
    SelectiveFieldExecutionContext<CompositeOutput>,
    ResolverOwnedSelectionsContext<CompositeOutput>,
    BaseRSSAwareFieldExecutionContextImpl<Q, Arguments, CompositeOutput>(
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
    override fun selections(): SelectionSet<CompositeOutput> = selectionSet()

    override fun ownedSelections(): SelectionSet<CompositeOutput> = ownedSelectionSet()
}
