package viaduct.tenant.runtime.jvm

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import viaduct.apiannotations.InternalApi
import viaduct.errors.TenantResolverException
import viaduct.errors.TenantUsageException
import viaduct.errors.handleTenantErrorsResultSuspend

@InternalApi
data class NodeBatchContext<C>(val context: C, val internalID: String)

private data class ResolverInput<S, C>(val selector: S, val context: C, val internalID: String)

@InternalApi
suspend fun <S, C, V, R> executeNodeBatch(
    selectors: List<S>,
    typeName: String,
    inputFor: (S) -> NodeBatchContext<C>,
    invoke: suspend (List<C>) -> Map<C, V>,
    unwrap: suspend (S, V) -> Result<R>,
): Map<S, Result<R>> {
    val inputs = selectors.map { selector ->
        val input = inputFor(selector)
        ResolverInput(selector, input.context, input.internalID)
    }
    val resolvedGroups = coroutineScope {
        partitionByUniqueKey(inputs) { it.internalID }
            .map { group -> async { resolveNodeBatchGroup(group, typeName, invoke, unwrap) } }
            .awaitAll()
    }
    return linkedMapOf<S, Result<R>>().apply {
        resolvedGroups.forEach { putAll(it) }
    }
}

private suspend fun <S, C, V, R> resolveNodeBatchGroup(
    group: List<ResolverInput<S, C>>,
    typeName: String,
    invoke: suspend (List<C>) -> Map<C, V>,
    unwrap: suspend (S, V) -> Result<R>,
): Map<S, Result<R>> =
    handleTenantErrorsResultSuspend(typeName) {
        val contexts = group.map { it.context }
        val results = invoke(contexts)
        if (contexts.size != results.size) {
            throw TenantResolverException(
                TenantUsageException(
                    "The batchResolve function in the Node resolver for $typeName was given a batch of size ${contexts.size} but returned ${results.size} elements"
                ),
                typeName,
            )
        }
        val contextToSelector = group.associate { it.context to it.selector }
        val resolved = linkedMapOf<S, Result<R>>()
        results.forEach { (returnedContext, value) ->
            val selector = contextToSelector[returnedContext]
                ?: throw TenantResolverException(
                    TenantUsageException(
                        "The batchResolve function in the Node resolver for $typeName returned a context that was not in the input context list: $returnedContext"
                    ),
                    typeName,
                )
            resolved[selector] = unwrap(selector, value)
        }
        resolved
    }.getOrElse { failure ->
        group.associate { input -> input.selector to Result.failure(failure) }
    }
