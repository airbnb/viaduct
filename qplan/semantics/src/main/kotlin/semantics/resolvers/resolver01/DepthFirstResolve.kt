package semantics.resolvers.resolver01

import model.ObjectEngineResult
import model.ResolverOccurrenceId
import model.SelectionForest
import model.requireQueryTypeDef
import semantics.shared.OEROccurrence
import semantics.shared.SharedOperationContext

/** Resolver01-03's recursive driver; orchestration and field tasks are shared with Resolver06-08. */
internal class DepthFirstResolve(
    operation: SharedOperationContext<*>,
    complete: (SelectionForest) -> SelectionForest,
) {
    private val dispatcher = DepthFirstTaskDispatcher()
    private val operation = DepthFirstOperationContext(operation, complete, dispatcher)

    /** Dispatches orchestration for a fresh Query root and resolves its accumulated fringe. */
    fun resolve(
        selections: SelectionForest,
        queryFragmentOwner: ResolverOccurrenceId? = null,
    ): ObjectEngineResult {
        val source = operation.world.resolverRegistry.createRootQueryInput()
        val result = ObjectEngineResult.of(operation.world.schema.requireQueryTypeDef(), mutable = true)
        val orchestration =
            DepthFirstOrchestrationTask.create(
                operation = operation,
                occurrence = OEROccurrence(result, emptyList(), result),
                source = source,
                constructionDemand = selections,
                queryOERDepth = 0,
            )
        queryFragmentOwner?.let { operation.resolverObserver.onQueryFragmentPrepared(it, result) }
        dispatcher.dispatchOrchestrator(orchestration)
        dispatcher.resolveOrchestrators()
        return result
    }
}
