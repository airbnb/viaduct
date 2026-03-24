package viaduct.api.testing

import viaduct.api.mocks.PrebakedResults
import viaduct.api.select.SelectionSet
import viaduct.api.types.CompositeOutput

/**
 * Creates an empty PrebakedResults that throws when accessed.
 *
 * Use this when no context query/mutation values are provided in test configuration.
 */
internal fun <T : CompositeOutput> emptyPrebakedResults(): PrebakedResults<T> {
    return object : PrebakedResults<T> {
        override fun get(selections: SelectionSet<T>): T {
            throw UnsupportedOperationException(
                "No pre-baked results were provided for context queries. " +
                    "Use contextQueryValues or contextMutationValues in test config to provide query results."
            )
        }
    }
}

/**
 * Creates a PrebakedResults that returns the same result for all selection sets.
 *
 * Use this when a single context query/mutation value is provided.
 */
internal fun <T : CompositeOutput> singleResultPrebakedResults(result: T): PrebakedResults<T> {
    return object : PrebakedResults<T> {
        override fun get(selections: SelectionSet<T>): T = result
    }
}
