package com.example.starwars.modules.universe.planets.queries

import com.example.starwars.modules.universe.planets.models.PlanetBuilder
import com.example.starwars.modules.universe.planets.models.PlanetsRepository
import com.example.starwars.universe.resolverbases.QueryResolvers
import jakarta.inject.Inject
import viaduct.api.Resolver
import viaduct.api.grts.PlanetsConnection
import viaduct.apiannotations.ExperimentalApi

/**
 * Connection resolver for the `allPlanetsConnection` query.
 *
 * Demonstrates **backward pagination** using [BackwardConnectionArguments] (`last`/`before`)
 * and the [ConnectionBuilder.fromList] builder. `fromList` is the best choice for backward
 * pagination because it accepts the entire dataset and automatically handles the "last N items"
 * logic — both with and without a `before` cursor — by examining [OffsetLimit.backwards].
 *
 * Example — last 3 planets (no cursor):
 * ```graphql
 * query {
 *   allPlanetsConnection(last: 3) {
 *     edges {
 *       cursor
 *       node { id name }
 *     }
 *     pageInfo {
 *       hasPreviousPage
 *       startCursor
 *     }
 *   }
 * }
 * ```
 *
 * Example — 3 planets before a cursor (paging backwards):
 * ```graphql
 * query {
 *   allPlanetsConnection(last: 3, before: "<startCursor from previous response>") {
 *     edges { cursor node { id name } }
 *     pageInfo { hasPreviousPage startCursor }
 *   }
 * }
 * ```
 */
@OptIn(ExperimentalApi::class)
@Resolver
class AllPlanetsConnectionQueryResolver
    @Inject
    constructor(
        private val planetsRepository: PlanetsRepository,
    ) : QueryResolvers.AllPlanetsConnection() {
        override suspend fun resolve(ctx: Context): PlanetsConnection? {
            val allPlanets = planetsRepository.findAll()
            // fromList handles both "last N" (backwards=true, no cursor) and
            // "last N before cursor" cases transparently via BackwardConnectionArguments.
            return PlanetsConnection.Builder(ctx)
                .fromList(allPlanets) { PlanetBuilder(ctx).build(it) }
                .build()
        }
    }
