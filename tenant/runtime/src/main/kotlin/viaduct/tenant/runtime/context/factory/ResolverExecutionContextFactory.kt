package viaduct.tenant.runtime.context.factory

import viaduct.api.context.ResolverExecutionContext
import viaduct.api.internal.InternalContext
import viaduct.api.internal.NodeReferenceGRTFactory
import viaduct.api.internal.select.SelectionSetFactory
import viaduct.api.internal.select.SelectionsLoader
import viaduct.api.types.Query
import viaduct.engine.api.EngineExecutionContext
import viaduct.tenant.runtime.context.ResolverExecutionContextImpl

class ResolverExecutionContextArgs(
    val internalContext: InternalContext,
    /** A service-scoped [SelectionSetFactory] */
    val selectionSetFactory: SelectionSetFactory,
    val engineExecutionContext: EngineExecutionContext,
    val resolverId: String,
    /** A service-scoped [SelectionsLoader.Factory] */
    val selectionsLoaderFactory: SelectionsLoader.Factory,
)

object ResolverExecutionContextFactory {
    /** create a [Factory<Args, ExecutionContext>] assembled from the provided factories */
    fun create(
        queryLoader: Factory<SelectionsLoaderArgs, SelectionsLoader<Query>> = SelectionsLoaderFactory.forQuery,
        nodeReferenceFactory: Factory<EngineExecutionContext, NodeReferenceGRTFactory> = NodeReferenceContextFactory.default,
    ): Factory<ResolverExecutionContextArgs, ResolverExecutionContext> =
        Factory { args ->
            ResolverExecutionContextImpl(
                args.internalContext,
                args.engineExecutionContext.requestContext,
                queryLoader(SelectionsLoaderArgs(resolverId = args.resolverId, selectionsLoaderFactory = args.selectionsLoaderFactory)),
                args.selectionSetFactory,
                nodeReferenceFactory(args.engineExecutionContext),
            )
        }

    /** A default `Factory<Args, ExecutionContext>` suitable for general use */
    val default: Factory<ResolverExecutionContextArgs, ResolverExecutionContext> = create()
}
