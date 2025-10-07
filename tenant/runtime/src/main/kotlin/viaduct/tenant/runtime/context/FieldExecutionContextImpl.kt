package viaduct.tenant.runtime.context

import viaduct.api.context.ResolverExecutionContext
import viaduct.api.internal.FieldExecutionContextTmp
import viaduct.api.internal.InternalContext
import viaduct.api.internal.internal
import viaduct.api.select.SelectionSet
import viaduct.api.types.Arguments
import viaduct.api.types.CompositeOutput
import viaduct.api.types.Object
import viaduct.api.types.Query

class FieldExecutionContextImpl<T : Object, Q : Query, A : Arguments, O : CompositeOutput>(
    private val executionContext: ResolverExecutionContext,
    override val objectValue: T,
    override val queryValue: Q,
    override val arguments: A,
    private val selections: SelectionSet<O>,
) : FieldExecutionContextTmp<T, Q, A, O>, ResolverExecutionContext by executionContext, InternalContext by executionContext.internal {
    override fun selections() = selections
}
