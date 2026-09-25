package semantics.resolvers.resolver21

import kotlinx.coroutines.coroutineScope
import model.ObjectEngineResult
import model.ResolverOccurrenceId
import model.SelectionForest
import model.schemaType
import semantics.shared.CycleCheckState
import semantics.shared.Demand
import semantics.shared.OEROccurrence
import semantics.shared.SharedOperationContext
import viaduct.engine.api.EngineObjectData

/** Resolves one operation through request-root tasks and exact value promises. */
internal class CoroutineResolve(
    private val operation: SharedOperationContext<*>,
    private val complete: (Demand<SelectionForest>) -> SelectionForest,
    private val cycleChecker: CycleCheckState = CycleCheckState.create(),
    private val supportsCheckerFragments: Boolean = false,
) {
    suspend fun resolve(source: EngineObjectData.Sync, selections: SelectionForest): ObjectEngineResult =
        coroutineScope {
            CoroutineOperationContext(
                operation,
                this,
                complete,
                cycleChecker,
                supportsCheckerFragments,
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
    val result = ObjectEngineResult.of(source.schemaType, mutable = true)
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
    dispatcher.dispatchOrchestrator(orchestration)
    return result
}
