package viaduct.service.runtime

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import graphql.ExecutionInput
import graphql.execution.preparsed.PreparsedDocumentEntry
import graphql.execution.preparsed.PreparsedDocumentProvider
import java.util.concurrent.CompletableFuture
import java.util.function.Function

internal class CachingPreparsedDocumentProvider : PreparsedDocumentProvider {
    // It shouldn't be a problem at all to have 10000 graphql queries in memory for a single
    // service. We can always tune this later, but it's unlikely that we would even have 10000
    // unique queries for a given service, providing folks are using query variables (which we will
    // lint against).
    private val cache: Cache<String, PreparsedDocumentEntry> = Caffeine.newBuilder().maximumSize(10000).build()

    override fun getDocumentAsync(
        executionInput: ExecutionInput,
        computeFunction: Function<ExecutionInput, PreparsedDocumentEntry>
    ): CompletableFuture<PreparsedDocumentEntry?> =
        CompletableFuture.completedFuture(
            cache.get(executionInput.query) {
                computeFunction.apply(executionInput)
            }
        )
}
