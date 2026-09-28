@file:Suppress("ForbiddenImport")

package semantics.resolvers.resolver23

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import model.ObjectEngineResult
import model.SelectionForest
import semantics.resolvers.resolver21.CoroutineResolve
import semantics.resolvers.successorDemandFromConstructionDemand
import semantics.shared.SharedOperationContext

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
            complete = { constructionDemand ->
                constructionDemand.successorDemandFromConstructionDemand(this@resolve)
            },
            supportsCheckerFragments = true,
        )
    return runBlocking {
        withTimeout(90_000) {
            resolver.resolve(source, selections)
        }
    }
}
