package viaduct.engine.runtime2.resolvers.resolver01

import viaduct.engine.runtime2.model.EngineResultCell
import viaduct.engine.runtime2.resolvers.GroundedFieldPublicationOccurrence

/** Defers object orchestration and executes fields immediately in their caller's dependency order. */
internal class DepthFirstTaskDispatcher : DepthFirstDispatcher {
    private var pending = mutableListOf<DepthFirstOrchestrationTask>()

    override fun dispatchOrchestration(task: DepthFirstOrchestrationTask) {
        pending += task
    }

    override fun dispatchFieldResolver(
        publication: GroundedFieldPublicationOccurrence<DepthFirstOperationContext>,
        queryOERDepth: Int,
    ) {
        DepthFirstFieldResolverTask.prepare(publication, queryOERDepth).run()
    }

    override suspend fun dispatchMutationField(
        publication: DepthFirstFieldResolverTask,
        cell: EngineResultCell,
    ) {
        publication.run()
        resolveOrchestrationFringe()
    }

    /**
     * Takes and clears the current fringe before entering any task. Shared passive recursion
     * dispatches children before their containing object, already in depth-first execution order.
     * A field's fringe must finish before its next dependent sibling may materialize inputs.
     */
    fun resolveOrchestrationFringe() {
        val fringe = pending
        pending = mutableListOf()
        fringe.forEach { it.run(::resolveOrchestrationFringe) }
    }
}
