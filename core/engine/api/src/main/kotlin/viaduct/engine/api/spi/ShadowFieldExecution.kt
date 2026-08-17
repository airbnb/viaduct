package viaduct.engine.api.spi

import graphql.GraphQLError
import viaduct.apiannotations.InternalApi

/**
 * The production and shadow outcomes from the temporary field-level shadow execution mechanism.
 *
 * This type is reserved for Airbnb-internal migration instrumentation and is expected to be
 * removed with that mechanism.
 */
@InternalApi
data class ShadowFieldExecutionResults(
    val production: Outcome,
    val shadow: Outcome,
) {
    /**
     * The raw resolver outcome from a field execution.
     *
     * The raw value is the resolver output used as input to GraphQL field completion. It does not
     * include completed GraphQL output or results from the client's nested selection set.
     * [graphqlErrors] contains errors explicitly returned alongside the raw resolver value; it does
     * not contain errors created later during GraphQL field completion.
     */
    @InternalApi
    data class Outcome(
        val rawValue: Result<Any?>,
        val graphqlErrors: List<GraphQLError>,
    )
}

/**
 * Receives production and shadow results for a field selected by Airbnb-internal instrumentation.
 *
 * Resolver and checker required selection sets execute normally, but mutable engine state and
 * errors are isolated from the production execution. Production field completion does not wait for
 * shadow execution. Unfinished shadow work is cancelled when the request completes, so [compare]
 * is invoked only if both raw outcomes become available while the request remains active. Its
 * invocation is not ordered relative to traversal of the production client's nested selection set.
 * Implementations must report mismatches internally and return normally; throwing is not a
 * supported mismatch signal.
 */
@InternalApi
fun interface ShadowFieldExecutionComparison {
    fun compare(results: ShadowFieldExecutionResults)

    companion object {
        /** Combines instrumentation callbacks so they can share one shadow field execution. */
        fun combine(comparisons: Iterable<ShadowFieldExecutionComparison>): ShadowFieldExecutionComparison? {
            val callbacks = comparisons.toList()
            if (callbacks.isEmpty()) {
                return null
            }
            if (callbacks.size == 1) {
                return callbacks.single()
            }
            return ShadowFieldExecutionComparison { results ->
                callbacks.forEach { comparison ->
                    comparison.compare(results)
                }
            }
        }
    }
}
