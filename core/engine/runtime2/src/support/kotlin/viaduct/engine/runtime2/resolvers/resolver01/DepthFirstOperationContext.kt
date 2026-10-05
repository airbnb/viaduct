package viaduct.engine.runtime2.resolvers.resolver01

import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.resolution.framework.OEROccurrence
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.engine.runtime2.resolution.framework.SharedTaskDispatcher
import viaduct.engine.runtime2.resolvers.GroundedFieldPublicationOccurrence

/** One depth-first resolution operation: shared state, demand policy, scheduling, and passive traversal. */
internal class DepthFirstOperationContext(
    operation: SharedOperationContext<*>,
    val complete: (SelectionForest) -> SelectionForest,
    dispatcher: DepthFirstDispatcher,
) : SharedOperationContext<DepthFirstDispatcher> by SharedOperationContext.create(
        world = operation.world,
        variableBindings = operation.variableBindings,
        resolverObserver = operation.resolverObserver,
        checkerObserver = operation.checkerObserver,
        dispatcher = dispatcher,
    ) {
    /** Passive traversal inherits the Query-OER depth of the task whose value it is traversing. */
    fun passiveValues(queryOERDepth: Int) = DepthFirstPassiveValueResolutionLogic(this, queryOERDepth)
}

/** Dispatcher contract shared by recursive and queued depth-first execution. */
internal interface DepthFirstDispatcher :
    SharedTaskDispatcher<
        DepthFirstOrchestrationTask,
        GroundedFieldPublicationOccurrence<DepthFirstOperationContext>,
        DepthFirstFieldResolverTask,
    > {
    /** Dispatches a field in the Query-OER scope identified by [queryOERDepth]. */
    fun dispatchFieldResolver(
        publication: GroundedFieldPublicationOccurrence<DepthFirstOperationContext>,
        queryOERDepth: Int,
    )

    /** Makes a prepared mutation namespace eligible to receive ordinary payload children. */
    fun mutationNamespacePrepared(occurrence: OEROccurrence) {}

    /** The shared dispatcher entry point starts at an independently rooted execution's depth. */
    override fun dispatchFieldResolver(publication: GroundedFieldPublicationOccurrence<DepthFirstOperationContext>) = dispatchFieldResolver(publication, queryOERDepth = 0)
}
