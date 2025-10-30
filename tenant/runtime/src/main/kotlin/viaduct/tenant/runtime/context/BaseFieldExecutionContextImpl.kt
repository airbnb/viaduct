package viaduct.tenant.runtime.context

import viaduct.api.context.BaseFieldExecutionContext
import viaduct.api.internal.InternalContext
import viaduct.api.select.SelectionSet
import viaduct.api.types.Arguments
import viaduct.api.types.CompositeOutput
import viaduct.api.types.Query

sealed class BaseFieldExecutionContextImpl(
    baseData: InternalContext,
    engineExecutionContextWrapper: EngineExecutionContextWrapper,
    private val selections: SelectionSet<CompositeOutput>,
    override val requestContext: Any?,
    override val arguments: Arguments,
    override val queryValue: Query,
) : BaseFieldExecutionContext<Query, Arguments, CompositeOutput>,
    ResolverExecutionContextImpl(baseData, engineExecutionContextWrapper) {
    override fun selections() = selections
}
