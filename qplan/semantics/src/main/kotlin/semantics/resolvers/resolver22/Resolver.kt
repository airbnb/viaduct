@file:Suppress("ForbiddenImport")

package semantics.resolvers.resolver22

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import model.ObjectEngineResult
import model.SelectionForest
import semantics.resolvers.resolver21.CoroutineResolve
import semantics.resolvers.successorBoundaryDemand
import semantics.shared.SharedOperationContext

/**
 * Resolves [selections] through structured coroutines with non-selective resolver applications.
 * Results may contain more OER nodes than are strictly necessary to resolve the query.
 */
fun SharedOperationContext<*>.resolve(selections: SelectionForest): ObjectEngineResult {
    require(!world.selectiveResolvers) {
        "Resolver22 requires non-selective resolvers"
    }
    val source = world.resolverRegistry.createRootQueryInput()
    val resolver =
        CoroutineResolve(
            operation = this@resolve,
            complete = { constructionDemand ->
                constructionDemand.values.successorBoundaryDemand(this@resolve)
            },
            supportsCheckerFragments = true,
        )
    return runBlocking {
        withTimeout(90_000) {
            resolver.resolve(source, selections)
        }
    }
}
