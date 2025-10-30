package viaduct.tenant.runtime.context

import viaduct.api.context.FieldExecutionContext
import viaduct.api.internal.InternalContext
import viaduct.api.select.SelectionSet
import viaduct.api.types.Arguments
import viaduct.api.types.CompositeOutput
import viaduct.api.types.Object
import viaduct.api.types.Query

class FieldExecutionContextImpl(
    baseData: InternalContext,
    engineExecutionContextWrapper: EngineExecutionContextWrapper,
    selections: SelectionSet<CompositeOutput>,
    requestContext: Any?,
    arguments: Arguments,
    override val objectValue: Object,
    queryValue: Query,
) : FieldExecutionContext<Object, Query, Arguments, CompositeOutput>,
    BaseFieldExecutionContextImpl(baseData, engineExecutionContextWrapper, selections, requestContext, arguments, queryValue)
