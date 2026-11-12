@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.resolution

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.suspendCancellableCoroutine
import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.model.MutationObjectEngineResult
import viaduct.engine.runtime2.model.MutationSelectionForest
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.engineObjectDataOf
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.schemaType
import viaduct.engine.runtime2.resolution.framework.CycleCheckState
import viaduct.engine.runtime2.resolution.framework.Demand
import viaduct.engine.runtime2.resolution.framework.MutationNamespaceOccurrence
import viaduct.engine.runtime2.resolution.framework.MutationOrchestrationTask
import viaduct.engine.runtime2.resolution.framework.OEROccurrence
import viaduct.engine.runtime2.resolution.framework.ResolutionTask
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.graphql.schema.ViaductSchema

/**
 * Starts one Resolution request and returns its live root result.
 *
 * The returned root has its complete selected key set installed and frozen, but its cell promises
 * may still be pending. All remaining work is owned by [requestScope].
 */
fun SharedOperationContext<*>.startResolve(
    selections: SelectionForest,
    requestScope: CoroutineScope,
): ObjectEngineResult = startResolution(selections, requestScope).root

/** Live root and completion of all work owned by this resolution. */
internal class StartedResolution(
    val root: ObjectEngineResult,
    private val completion: Job,
) {
    suspend fun await(): Unit =
        suspendCancellableCoroutine { continuation ->
            val subscription = completion.invokeOnCompletion { cause ->
                continuation.resumeWith(if (cause == null) Result.success(Unit) else Result.failure(cause))
            }
            continuation.invokeOnCancellation { subscription.dispose() }
        }
}

internal fun SharedOperationContext<*>.startResolution(
    selections: SelectionForest,
    requestScope: CoroutineScope,
    cycleChecker: CycleCheckState = CycleCheckState.create(),
    rootType: ViaductSchema.Object = world.schema.requireQueryTypeDef(),
): StartedResolution {
    val completion = Job(requestScope.coroutineContext[Job])
    val resolutionScope = CoroutineScope(requestScope.coroutineContext + completion)
    var started = false
    return try {
        val resolutionOperation =
            OperationContext.create(
                base = this@startResolution,
                requestScope = resolutionScope,
                cycleChecker = cycleChecker,
            )
        val root = resolutionOperation.startResolve(selections, rootType)
        completion.complete()
        started = true
        StartedResolution(root, completion)
    } catch (cause: Exception) {
        completion.completeExceptionally(cause)
        throw cause
    } finally {
        if (!started) {
            completion.cancel()
            requestScope.coroutineContext[Job]?.cancel(CancellationException("Resolution failed to start"))
        }
    }
}

/** Starts another independently rooted execution in an existing logical operation. */
internal fun OperationContext.startResolve(
    selections: SelectionForest,
    rootType: ViaductSchema.Object = world.schema.requireQueryTypeDef(),
    creator: ResolutionTask? = null,
): ObjectEngineResult {
    if (selections is MutationSelectionForest) {
        require(selections.type == world.schema.mutationTypeDef) { "Mutation execution must start at the Mutation root" }
        val result = MutationObjectEngineResult.of(selections)
        val task = MutationOrchestrationTask.create(
            operation = this,
            namespace = MutationNamespaceOccurrence(OEROccurrence(result, emptyList(), result), selections),
            prepareField = { task, occurrence, selection -> FieldResolverTask.prepareMutation(this, occurrence, selection, task) },
            namespacePrepared = { bindingsState.markBindingsDeclared(it.target) },
            creator = creator,
        )
        dispatcher.dispatchMutationOrchestration(task)
        return result
    }
    val input = if (rootType == world.schema.requireQueryTypeDef()) {
        world.resolverRegistry.createRootQueryInput()
    } else {
        require(rootType == world.schema.subscriptionTypeDef) { "Expected a Query or Subscription root" }
        engineObjectDataOf(rootType)
    }
    return startResolve(Demand.checked(selections), input, creator)
}

internal fun OperationContext.startResolve(
    selections: Demand<SelectionForest>,
    source: EngineObjectData.Sync = world.resolverRegistry.createRootQueryInput(),
    creator: ResolutionTask? = null,
): ObjectEngineResult {
    val result: ObjectEngineResult =
        OrchestrationTask.createObjectResult(this, source.schemaType(world.schema), selections)
    val orchestration =
        OrchestrationTask.create(
            operation = this@startResolve,
            occurrence =
                OEROccurrence(
                    root = result,
                    path = emptyList(),
                    target = result,
                ),
            source = source,
            constructionDemand = selections,
            creator = creator,
        )
    dispatcher.dispatchOrchestration(orchestration)
    return result
}
