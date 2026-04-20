package com.example.starwars.modules.universe.species.queries

import com.example.starwars.modules.universe.species.models.SpeciesBuilder
import com.example.starwars.modules.universe.species.models.SpeciesRepository
import com.example.starwars.universe.resolverbases.QueryResolvers
import jakarta.inject.Inject
import viaduct.api.Resolver
import viaduct.api.connection.OffsetCursor
import viaduct.api.grts.SpeciesConnection
import viaduct.api.grts.SpeciesEdge
import viaduct.apiannotations.ExperimentalApi

/**
 * Connection resolver for the `allSpeciesConnection` query.
 *
 * Demonstrates **multidirectional pagination** using [MultidirectionalConnectionArguments]
 * (`first`/`after` OR `last`/`before`) and the [ConnectionBuilder.fromEdges] builder.
 *
 * `fromEdges` gives you complete manual control:
 * - You decide which slice to fetch and how to compute `hasNextPage`/`hasPreviousPage`.
 * - You encode each edge's cursor yourself using [OffsetCursor.fromOffset].
 * - Useful when cursor values come from an external source, or when you need custom
 *   cursor semantics beyond simple offsets.
 *
 * This resolver demonstrates the proper pattern for backward pagination using
 * [requiresTotalCountForOffsetLimit] and [toOffsetLimit(totalCount)]:
 * - Check if the total count is needed via `requiresTotalCountForOffsetLimit()`
 * - If so, fetch the total count and use `toOffsetLimit(totalCount)` to get the correct offset
 * - This avoids needing to manually handle the `backwards` flag
 *
 * Example — forward (first 2 species):
 * ```graphql
 * query {
 *   allSpeciesConnection(first: 2) {
 *     edges { cursor node { id name classification } }
 *     pageInfo { hasNextPage endCursor }
 *   }
 * }
 * ```
 *
 * Example — backward (last 2 species):
 * ```graphql
 * query {
 *   allSpeciesConnection(last: 2) {
 *     edges { cursor node { id name } }
 *     pageInfo { hasPreviousPage startCursor }
 *   }
 * }
 * ```
 *
 * Example — paging backward from a cursor:
 * ```graphql
 * query {
 *   allSpeciesConnection(last: 2, before: "<startCursor from previous response>") {
 *     edges { cursor node { id name } }
 *     pageInfo { hasPreviousPage startCursor }
 *   }
 * }
 * ```
 */
@OptIn(ExperimentalApi::class)
@Resolver
class AllSpeciesConnectionQueryResolver
    @Inject
    constructor(
        private val speciesRepository: SpeciesRepository,
    ) : QueryResolvers.AllSpeciesConnection() {
        override suspend fun resolve(ctx: Context): SpeciesConnection? {
            // Use requiresTotalCountForOffsetLimit() to check if we need total count for offset calculation.
            // This is true when using backward pagination with only `last` (no `before` cursor).
            val offsetLimit = if (ctx.arguments.requiresTotalCountForOffsetLimit()) {
                // When total count is required, toOffsetLimit(totalCount) computes the correct offset
                // (e.g., totalCount - last) without needing the `backwards` flag.
                val totalCount = speciesRepository.count()
                ctx.arguments.toOffsetLimit(totalCount = totalCount)
            } else {
                ctx.arguments.toOffsetLimit()
            }

            val slicePlusOne = speciesRepository.findSome(offsetLimit.limit + 1, offsetLimit.offset)
            val hasNextPage = slicePlusOne.size > offsetLimit.limit
            val hasPreviousPage = offsetLimit.offset > 0

            val edges =
                slicePlusOne.take(offsetLimit.limit).mapIndexed { idx, species ->
                    SpeciesEdge.Builder(ctx)
                        .node(SpeciesBuilder(ctx).build(species))
                        .cursor(OffsetCursor.fromOffset(offsetLimit.offset + idx).value)
                        .build()
                }

            return SpeciesConnection.Builder(ctx)
                .fromEdges(edges, hasNextPage = hasNextPage, hasPreviousPage = hasPreviousPage)
                .build()
        }
    }
