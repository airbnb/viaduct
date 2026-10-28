@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.resolvers.resolver21

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext

/**
 * Resolves [selections] with structured coroutines when resolver object fragments are empty.
 *
 * Precondition: `world.schema` has no `@parent` fields.
 */
fun SharedOperationContext<*>.resolve(selections: SelectionForest): ObjectEngineResult {
    require(!world.selectiveResolvers) {
        "Resolver21 requires non-selective resolvers"
    }
    val source = world.resolverRegistry.createRootQueryInput()
    val resolver =
        CoroutineResolve(
            operation = this@resolve,
            complete = { constructionDemand, _ -> constructionDemand.values },
        )
    return runBlocking {
        withTimeout(90_000) {
            resolver.resolve(source, selections)
        }
    }
}
