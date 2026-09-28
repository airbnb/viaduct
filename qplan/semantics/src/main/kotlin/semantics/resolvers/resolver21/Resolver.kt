@file:Suppress("ForbiddenImport")

package semantics.resolvers.resolver21

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import model.ObjectEngineResult
import model.SelectionForest
import semantics.shared.SharedOperationContext

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
            complete = { constructionDemand -> constructionDemand.values },
        )
    return runBlocking {
        withTimeout(90_000) {
            resolver.resolve(source, selections)
        }
    }
}
