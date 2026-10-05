@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.resolution

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import viaduct.engine.runtime2.model.MutationObjectEngineResult
import viaduct.engine.runtime2.model.MutationSelectionForest
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.schemaType
import viaduct.engine.runtime2.resolution.framework.Demand
import viaduct.engine.runtime2.resolution.framework.MutationNamespaceOccurrence
import viaduct.engine.runtime2.resolution.framework.MutationOrchestrationTask
import viaduct.engine.runtime2.resolution.framework.OEROccurrence
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext

/**
 * Resolves selective demand once per object-local symbolic key on [coroutineContext].
 *
 * Keys coalesce when their fields and argument expressions are equal. Variables in those
 * expressions identify their owning resolver occurrences, so equal uses of one variable instance
 * coalesce while variables owned by different resolver occurrences remain distinct. The caller
 * owns the context and its execution resources; the resolver neither retains nor closes them.
 */
fun SharedOperationContext<*>.resolve(
    selections: SelectionForest,
    coroutineContext: CoroutineContext,
): ObjectEngineResult =
    runBlocking(coroutineContext) {
        withTimeout(15_000) {
            coroutineScope {
                startResolve(
                    selections = selections,
                    requestScope = this,
                )
            }
        }
    }

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

@Suppress("TooGenericExceptionCaught") // Scope cleanup must also handle fatal startup failures.
internal fun SharedOperationContext<*>.startResolution(
    selections: SelectionForest,
    requestScope: CoroutineScope,
): StartedResolution {
    require(world.selectiveResolvers) {
        "Resolution requires selective resolvers"
    }
    val completion = Job(requestScope.coroutineContext[Job])
    val resolutionScope = CoroutineScope(requestScope.coroutineContext + completion)
    return try {
        val resolutionOperation =
            OperationContext.create(
                base = this@startResolution,
                requestScope = resolutionScope,
            )
        val root = resolutionOperation.startResolve(selections)
        completion.complete()
        StartedResolution(root, completion)
    } catch (cause: Throwable) {
        completion.completeExceptionally(cause)
        throw cause
    }
}

/** Starts another independently rooted execution in an existing logical operation. */
internal fun OperationContext.startResolve(selections: SelectionForest): ObjectEngineResult {
    if (selections is MutationSelectionForest) {
        require(selections.type == world.schema.mutationTypeDef) { "Mutation execution must start at the Mutation root" }
        val result = MutationObjectEngineResult.of(selections)
        val task = MutationOrchestrationTask.create(
            operation = this,
            namespace = MutationNamespaceOccurrence(OEROccurrence(result, emptyList(), result), selections),
            prepareField = { occurrence, selection -> FieldResolverTask.prepareMutation(this, occurrence, selection) },
            namespacePrepared = { bindingsState.markBindingsDeclared(it.target) },
        )
        dispatcher.dispatchMutationOrchestration(task)
        return result
    }
    return startResolve(Demand.checked(selections))
}

internal fun OperationContext.startResolve(selections: Demand<SelectionForest>): ObjectEngineResult {
    val source = world.resolverRegistry.createRootQueryInput()
    val result: ObjectEngineResult =
        OrchestrationTask.createObjectResult(this, source.schemaType, selections)
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
        )
    dispatcher.dispatchOrchestration(orchestration)
    return result
}
