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
 * How [toOffsetLimit] works for multidirectional args:
 * - `first=N, after=<cursor>`: forward page — offset decoded from cursor, limit = N.
 * - `last=N, before=<cursor>`: backward page — offset = cursorOffset - N, limit = N, backwards = false.
 * - `last=N` (no before cursor): "last N items" — offset = 0, limit = N, backwards = true.
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
            val offsetLimit = ctx.arguments.toOffsetLimit()

            // When backwards=true (last N without a before cursor), toOffsetLimit returns
            // offset=0. We must resolve the real start index from the total count ourselves.
            val effectiveOffset =
                if (offsetLimit.backwards) {
                    maxOf(0, speciesRepository.count() - offsetLimit.limit)
                } else {
                    offsetLimit.offset
                }

            val slicePlusOne = speciesRepository.findSome(offsetLimit.limit + 1, effectiveOffset)
            val hasNextPage = !offsetLimit.backwards && slicePlusOne.size > offsetLimit.limit
            val hasPreviousPage = effectiveOffset > 0

            val edges =
                slicePlusOne.take(offsetLimit.limit).mapIndexed { idx, species ->
                    SpeciesEdge.Builder(ctx)
                        .node(SpeciesBuilder(ctx).build(species))
                        .cursor(OffsetCursor.fromOffset(effectiveOffset + idx).value)
                        .build()
                }

            return SpeciesConnection.Builder(ctx)
                .fromEdges(edges, hasNextPage = hasNextPage, hasPreviousPage = hasPreviousPage)
                .build()
        }
    }
