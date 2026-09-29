@file:Suppress("ForbiddenImport")

package semantics.resolver26

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import model.ObjectEngineResult
import model.SelectionForest
import model.schemaType
import semantics.shared.Demand
import semantics.shared.OEROccurrence
import semantics.shared.SharedOperationContext

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
 * Starts one Resolver26 request and returns its live root result.
 *
 * The returned root has its complete selected key set installed and frozen, but its cell promises
 * may still be pending. All remaining work is owned by [requestScope].
 */
fun SharedOperationContext<*>.startResolve(
    selections: SelectionForest,
    requestScope: CoroutineScope,
): ObjectEngineResult {
    require(world.selectiveResolvers) {
        "Resolver26 requires selective resolvers"
    }
    val resolver26Operation =
        OperationContext.create(
            base = this@startResolve,
            requestScope = requestScope,
        )
    return resolver26Operation.startResolve(selections)
}

/** Starts another independently rooted Query execution in an existing logical operation. */
internal fun OperationContext.startResolve(selections: SelectionForest): ObjectEngineResult = startResolve(Demand.checked(selections))

internal fun OperationContext.startResolve(selections: Demand<SelectionForest>): ObjectEngineResult {
    val source = world.resolverRegistry.createRootQueryInput()
    val result: ObjectEngineResult =
        ObjectEngineResult.of(
            type = source.schemaType,
            mutable = true,
        )
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
