package viaduct.tenant.runtime.context

import viaduct.api.context.MutationFieldExecutionContext
import viaduct.api.internal.InternalContext
import viaduct.api.select.SelectionSet
import viaduct.api.types.Arguments
import viaduct.api.types.CompositeOutput
import viaduct.api.types.Mutation
import viaduct.api.types.Object
import viaduct.api.types.Query

/**
 * Note the primary constructor is for testing purposes, to allow you
 * to more easily mock out the engine execution context.  Production
 * use cases should use the secondary constructor.
 */
class MutationFieldExecutionContextImpl(
    baseData: InternalContext,
    engineExecutionContextWrapper: EngineExecutionContextWrapper,
    selections: SelectionSet<CompositeOutput>,
    requestContext: Any?,
    arguments: Arguments,
    objectValue: Object,
    queryValue: Query,
) : MutationFieldExecutionContext<Object, Query, Arguments, CompositeOutput>,
    SealedFieldExecutionContextImpl(baseData, engineExecutionContextWrapper, selections, requestContext, arguments, objectValue, queryValue) {
    override suspend fun <T : Mutation> mutation(selections: SelectionSet<T>) = engineExecutionContextWrapper.mutation(this, "mutation", selections)
}
