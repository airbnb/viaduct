package viaduct.api.testing.types

import viaduct.api.types.Query

/**
 * Wrapper around a [Query] that is used to distinguish between different selections in the context query map.
 * Pass in one (or more) of these to ResolverTestBase.runFieldResolver, ResolverTestBase.runNodeResolver,
 * and the other related methods to mock out the query results for a specific selection set.
 */
class QueryForSelection(
    val selections: String,
    val query: Query
) : Query by query
