package viaduct.tenant.runtime.context2

import viaduct.api.internal.FieldExecutionContextTmp
import viaduct.api.internal.InternalContext
import viaduct.api.select.SelectionSet
import viaduct.api.types.Arguments
import viaduct.api.types.CompositeOutput
import viaduct.api.types.Object
import viaduct.api.types.Query
import viaduct.engine.api.EngineExecutionContext

/**
 * Needed to "close" our implementation hierarchy
 */
sealed class SealedFieldExecutionContextImpl(
    baseData: InternalContext,
    engineExecutionContextWrapper: EngineExecutionContextWrapper,
    private val selections: SelectionSet<CompositeOutput>,
    override val arguments: Arguments,
    override val objectValue: Object,
    override val queryValue: Query,
) : FieldExecutionContextTmp<Object, Query, Arguments, CompositeOutput>,
    ResolverExecutionContextImpl(baseData, engineExecutionContextWrapper) {
    override fun selections() = selections
}

class FieldExecutionContextImpl(
    baseData: InternalContext,
    engineExecutionContextWrapper: EngineExecutionContextWrapper,
    selections: SelectionSet<CompositeOutput>,
    arguments: Arguments,
    objectValue: Object,
    queryValue: Query,
) : SealedFieldExecutionContextImpl(baseData, engineExecutionContextWrapper, selections, arguments, objectValue, queryValue) {
    constructor(
        baseData: InternalContext,
        engineExecutionContext: EngineExecutionContext,
        selections: SelectionSet<CompositeOutput>,
        arguments: Arguments,
        objectValue: Object,
        queryValue: Query,
    ) : this(
        baseData,
        EngineExecutionContextWrapperImpl(engineExecutionContext),
        selections,
        arguments,
        objectValue,
        queryValue,
    )
}
