package semantics.resolvers.resolver01

import semantics.resolvers.GroundedFieldPublicationOccurrence

/** Defers object orchestration and executes fields immediately in their caller's dependency order. */
internal class DepthFirstTaskDispatcher : DepthFirstDispatcher {
    private var pending = mutableListOf<DepthFirstOrchestrationTask>()

    override fun dispatchOrchestrator(task: DepthFirstOrchestrationTask) {
        pending += task
    }

    override fun dispatchFieldResolver(
        publication: GroundedFieldPublicationOccurrence<DepthFirstOperationContext>,
        queryOERDepth: Int,
    ) {
        DepthFirstFieldResolverTask.create(publication, queryOERDepth).run()
    }

    /**
     * Takes and clears the current fringe before entering any task. Shared passive recursion
     * dispatches children before their containing object, already in depth-first execution order.
     * A field's fringe must finish before its next dependent sibling may materialize inputs.
     */
    fun resolveOrchestrators() {
        val fringe = pending
        pending = mutableListOf()
        fringe.forEach { it.run(::resolveOrchestrators) }
    }
}
