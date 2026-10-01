@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.resolvers.resolver22

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.engine.runtime2.resolvers.resolver21.CoroutineResolve
import viaduct.engine.runtime2.resolvers.successorBoundaryDemand

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
            complete = { constructionDemand, _ ->
                constructionDemand.values.successorBoundaryDemand(this@resolve)
            },
        )
    return runBlocking {
        withTimeout(90_000) {
            resolver.resolve(source, selections)
        }
    }
}
