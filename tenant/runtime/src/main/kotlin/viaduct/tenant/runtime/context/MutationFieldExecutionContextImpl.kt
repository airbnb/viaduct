package viaduct.tenant.runtime.context

import viaduct.api.context.FieldExecutionContext
import viaduct.api.context.MutationFieldExecutionContext
import viaduct.api.internal.InternalContext
import viaduct.api.internal.select.SelectionsLoader
import viaduct.api.select.SelectionSet
import viaduct.api.types.Arguments
import viaduct.api.types.CompositeOutput
import viaduct.api.types.Mutation
import viaduct.api.types.Object
import viaduct.api.types.Query

class MutationFieldExecutionContextImpl<T : Object, Q : Query, A : Arguments, O : CompositeOutput>(
    private val inner: FieldExecutionContextImpl<T, Q, A, O>,
    private val mutationLoader: SelectionsLoader<Mutation>,
) : FieldExecutionContext<T, Q, A, O> by inner, MutationFieldExecutionContext<T, Q, A, O>, InternalContext by inner {
    override suspend fun <T : Mutation> mutation(selections: SelectionSet<T>): T = mutationLoader.load(this, selections)
}
