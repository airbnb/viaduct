package com.example.starwars.modules.universe.planets.queries

import com.example.starwars.modules.universe.planets.models.PlanetBuilder
import com.example.starwars.modules.universe.planets.models.PlanetsRepository
import com.example.starwars.modules.universe.species.queries.DEFAULT_PAGE_SIZE
import com.example.starwars.universe.resolverbases.QueryResolvers
import io.micronaut.context.annotation.Prototype
import jakarta.inject.Inject
import viaduct.api.grts.Planet
import viaduct.api.resolver.Resolver

/**
 * Resolver for fetching a list of planets.
 *
 * This class extends the `QueryResolvers.AllPlanets` base class and provides
 * the implementation for resolving all planets with an optional limit.
 */
@Resolver
@Prototype
class AllPlanetsQueryResolver
    @Inject
    constructor(
        private val planetsRepository: PlanetsRepository
    ) : QueryResolvers.AllPlanets() {
        override suspend fun resolve(ctx: Context): List<Planet?>? {
            // The limit comes from arguments, or we take the default.
            val limit = ctx.arguments.limit ?: DEFAULT_PAGE_SIZE

            // Fetch planets from the repository.
            val planets = planetsRepository.findAll().take(limit)

            // Map the fetched planets to the Viaduct generated class.
            return planets.map(PlanetBuilder(ctx)::build)
        }
    }
