package viaduct.tenant.runtime.context.factory

import viaduct.api.context.MutationFieldExecutionContext
import viaduct.api.internal.select.SelectionsLoader
import viaduct.api.types.Mutation
import viaduct.tenant.runtime.context.FieldExecutionContextImpl
import viaduct.tenant.runtime.context.MutationFieldExecutionContextImpl

fun interface MutationFieldExecutionContextFactory : FieldExecutionContextFactory {
    override fun make(args: FieldArgs): MutationFieldExecutionContext<*, *, *, *>
}

object MutationFieldExecutionContextMetaFactory {
    /**
     * Create a [Factory] that will always wrap the output of [fieldContext]
     * in a MutationExecutionContext.
     */
    fun create(
        fieldContext: FieldExecutionContextFactory,
        mutationSelectionsLoader: Factory<SelectionsLoaderArgs, SelectionsLoader<Mutation>> = SelectionsLoaderFactory.forMutation,
    ): MutationFieldExecutionContextFactory =
        MutationFieldExecutionContextFactory { args ->
            MutationFieldExecutionContextImpl(
                fieldContext.make(args) as FieldExecutionContextImpl,
                mutationSelectionsLoader.mk(
                    SelectionsLoaderArgs(
                        resolverId = args.resolverId,
                        selectionsLoaderFactory = args.selectionsLoaderFactory,
                    )
                )
            )
        }
}
