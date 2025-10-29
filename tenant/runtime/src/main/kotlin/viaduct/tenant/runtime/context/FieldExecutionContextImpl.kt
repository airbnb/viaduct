package viaduct.tenant.runtime.context

import viaduct.api.context.FieldExecutionContext
import viaduct.api.internal.InternalContext
import viaduct.api.select.SelectionSet
import viaduct.api.types.Arguments
import viaduct.api.types.CompositeOutput
import viaduct.api.types.Object
import viaduct.api.types.Query

/**
 * Needed to "close" our implementation hierarchy
 */
sealed class SealedFieldExecutionContextImpl(
    baseData: InternalContext,
    engineExecutionContextWrapper: EngineExecutionContextWrapper,
    private val selections: SelectionSet<CompositeOutput>,
    override val requestContext: Any?,
    override val arguments: Arguments,
    override val objectValue: Object,
    override val queryValue: Query,
) : FieldExecutionContext<Object, Query, Arguments, CompositeOutput>,
    ResolverExecutionContextImpl(baseData, engineExecutionContextWrapper) {
    override fun selections() = selections
}

class FieldExecutionContextImpl(
    baseData: InternalContext,
    engineExecutionContextWrapper: EngineExecutionContextWrapper,
    selections: SelectionSet<CompositeOutput>,
    requestContext: Any?,
    arguments: Arguments,
    objectValue: Object,
    queryValue: Query,
) : SealedFieldExecutionContextImpl(baseData, engineExecutionContextWrapper, selections, requestContext, arguments, objectValue, queryValue)
