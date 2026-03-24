package viaduct.engine.api

import graphql.execution.directives.QueryDirectives

/**
 * Context for CheckerResult.
 */
data class CheckerResultContext(
    /**
     * Query directives applied to the field in the resolver query.
     */
    val fieldQueryDirectives: QueryDirectives? = null,
)
