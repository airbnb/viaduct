@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.resolvers.resolver23

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.engine.runtime2.resolvers.resolver21.CoroutineResolve
import viaduct.engine.runtime2.resolvers.successorDemandFromConstructionDemand

/**
 * Resolves [selections] through structured coroutines with selective resolver applications. Whether
 * the results contain only the necessary OER nodes has not been proved.
 */
fun SharedOperationContext<*>.resolve(selections: SelectionForest): ObjectEngineResult {
    require(world.selectiveResolvers) {
        "Resolver23 requires selective resolvers"
    }
    val source = world.resolverRegistry.createRootQueryInput()
    val resolver =
        CoroutineResolve(
            operation = this@resolve,
            complete = { constructionDemand, possibleRootTypes ->
                constructionDemand.successorDemandFromConstructionDemand(
                    this@resolve,
                    possibleRootTypes,
                )
            },
        )
    return runBlocking {
        withTimeout(90_000) {
            resolver.resolve(source, selections)
        }
    }
}
