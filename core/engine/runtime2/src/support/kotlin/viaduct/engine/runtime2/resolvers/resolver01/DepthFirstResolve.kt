package viaduct.engine.runtime2.resolvers.resolver01

import viaduct.engine.runtime2.model.MutationObjectEngineResult
import viaduct.engine.runtime2.model.MutationSelectionForest
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ResolverOccurrenceId
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.resolution.framework.MutationNamespaceOccurrence
import viaduct.engine.runtime2.resolution.framework.OEROccurrence
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext

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
        if (selections is MutationSelectionForest) {
            require(selections.type == operation.world.schema.mutationTypeDef) { "Mutations must start at their root" }
            val result = MutationObjectEngineResult.of(selections)
            DepthFirstMutationTask(operation, MutationNamespaceOccurrence(OEROccurrence(result, emptyList(), result), selections))
                .run()
            return result
        }
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
        queryFragmentOwner?.let {
            operation.resolverObserver.onIndependentQueryFragmentPrepared(it, result)
        }
        dispatcher.dispatchOrchestration(orchestration)
        dispatcher.resolveOrchestrationFringe()
        return result
    }
}
