package viaduct.tenant.runtime.context

import viaduct.api.context.MutationFieldExecutionContext
import viaduct.api.internal.InternalContext
import viaduct.api.select.SelectionSet
import viaduct.api.types.Arguments
import viaduct.api.types.CompositeOutput
import viaduct.api.types.Mutation
import viaduct.api.types.Query

class MutationFieldExecutionContextImpl(
    baseData: InternalContext,
    engineExecutionContextWrapper: EngineExecutionContextWrapper,
    selections: SelectionSet<CompositeOutput>,
    requestContext: Any?,
    arguments: Arguments,
    queryValue: Query,
) : MutationFieldExecutionContext<Query, Arguments, CompositeOutput>,
    BaseFieldExecutionContextImpl(baseData, engineExecutionContextWrapper, selections, requestContext, arguments, queryValue) {
    override suspend fun <T : Mutation> mutation(selections: SelectionSet<T>) = engineExecutionContextWrapper.mutation(this, "mutation", selections)
}
