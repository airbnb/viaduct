package com.example.starwars.modules.universe.starships.queries

import com.example.starwars.modules.universe.starships.models.StarshipBuilder
import com.example.starwars.modules.universe.starships.models.StarshipsRepository
import com.example.starwars.universe.resolverbases.QueryResolvers
import jakarta.inject.Inject
import viaduct.api.Resolver
import viaduct.api.grts.StarshipsConnection
import viaduct.apiannotations.ExperimentalApi

/**
 * Connection resolver for the `allStarshipsConnection` query.
 *
 * Demonstrates forward pagination using [ForwardConnectionArguments] (`first`/`after`)
 * and the [ConnectionBuilder.fromSlice] builder. `fromSlice` is ideal when the caller
 * pre-computes the slice from a larger dataset — the classic pattern is to fetch `limit+1`
 * items so you can detect `hasNextPage` without loading the entire collection.
 *
 * How `fromSlice` works:
 * - Caller fetches `limit + 1` items starting at the resolved offset.
 * - If `slicePlusOne.size > limit`, there is a next page; discard the extra item.
 * - `fromSlice` internally takes at most `limit` items, encodes offset-based cursors
 *   for each edge, and sets `hasPreviousPage = (offset > 0)`.
 *
 * Example — first 4 starships:
 * ```graphql
 * query {
 *   allStarshipsConnection(first: 4) {
 *     edges {
 *       cursor
 *       node { id name model }
 *     }
 *     pageInfo {
 *       hasNextPage
 *       endCursor
 *     }
 *   }
 * }
 * ```
 *
 * Example — next 4 starships using the endCursor from above:
 * ```graphql
 * query {
 *   allStarshipsConnection(first: 4, after: "<endCursor>") {
 *     edges { cursor node { id name } }
 *     pageInfo { hasNextPage hasPreviousPage endCursor }
 *   }
 * }
 * ```
 */
@OptIn(ExperimentalApi::class)
@Resolver
class AllStarshipsConnectionQueryResolver
    @Inject
    constructor(
        private val starshipsRepository: StarshipsRepository,
    ) : QueryResolvers.AllStarshipsConnection() {
        override suspend fun resolve(ctx: Context): StarshipsConnection? {
            // Resolve the offset and page size from the forward pagination arguments.
            val (offset, limit) = ctx.arguments.toOffsetLimit()

            // Fetch limit+1 items: the extra item lets us detect hasNextPage cheaply
            // without loading the full dataset or issuing a separate count query.
            val slicePlusOne = starshipsRepository.findAll().drop(offset).take(limit + 1)
            val hasNextPage = slicePlusOne.size > limit

            // fromSlice takes at most `limit` items, encodes cursors, and sets PageInfo.
            return StarshipsConnection.Builder(ctx)
                .fromSlice(slicePlusOne, hasNextPage) { StarshipBuilder(ctx).build(it) }
                .build()
        }
    }
