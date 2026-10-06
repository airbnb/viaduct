package viaduct.engine.runtime2.resolvers.resolver21

import kotlinx.coroutines.coroutineScope
import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.model.MutationObjectEngineResult
import viaduct.engine.runtime2.model.MutationSelectionForest
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ResolverOccurrenceId
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.schemaType
import viaduct.engine.runtime2.resolution.framework.CycleCheckState
import viaduct.engine.runtime2.resolution.framework.Demand
import viaduct.engine.runtime2.resolution.framework.MutationNamespaceOccurrence
import viaduct.engine.runtime2.resolution.framework.MutationOrchestrationTask
import viaduct.engine.runtime2.resolution.framework.OEROccurrence
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.graphql.schema.ViaductSchema

/** Resolves one operation through request-root tasks and exact value promises. */
internal class CoroutineResolve(
    private val operation: SharedOperationContext<*>,
    private val complete: (Demand<SelectionForest>, Set<ViaductSchema.Object>) -> SelectionForest,
    private val cycleChecker: CycleCheckState = CycleCheckState.create(),
) {
    suspend fun resolve(
        source: EngineObjectData.Sync,
        selections: SelectionForest
    ): ObjectEngineResult =
        coroutineScope {
            CoroutineOperationContext(
                operation,
                this,
                complete,
                cycleChecker,
            ).startResolve(
                source = source,
                demand = Demand.checked(selections),
            )
        }
}

/** Prepares and dispatches a fresh Query root; its fields remain owned by the request scope. */
internal fun CoroutineOperationContext.startResolve(
    source: EngineObjectData.Sync,
    demand: Demand<SelectionForest>,
    queryFragmentOwner: ResolverOccurrenceId? = null,
): ObjectEngineResult {
    val selections = demand.values
    if (selections is MutationSelectionForest) {
        require(selections.type == world.schema.mutationTypeDef) { "Mutations must start at their root" }
        val result = MutationObjectEngineResult.of(selections)
        val task = MutationOrchestrationTask.create(
            operation = this,
            namespace = MutationNamespaceOccurrence(OEROccurrence(result, emptyList(), result), selections),
            prepareField = { occurrence, selection -> CoroutineFieldResolverTask.prepareMutation(this, occurrence, selection) },
        )
        dispatcher.dispatchMutationOrchestration(task)
        return result
    }
    val result = CoroutineOrchestrationTask.createObjectResult(this, source.schemaType, demand)
    val orchestration =
        CoroutineOrchestrationTask.create(
            this@startResolve,
            OEROccurrence(result, emptyList(), result),
            source,
            demand,
        )
    queryFragmentOwner?.let {
        resolverObserver.onIndependentQueryFragmentPrepared(it, result)
    }
    dispatcher.dispatchOrchestration(orchestration)
    return result
}
