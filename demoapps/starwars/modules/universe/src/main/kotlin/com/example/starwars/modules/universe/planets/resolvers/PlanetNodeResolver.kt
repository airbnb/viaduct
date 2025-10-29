package com.example.starwars.modules.universe.planets.resolvers

import com.example.starwars.modules.universe.planets.models.PlanetBuilder
import com.example.starwars.modules.universe.planets.models.PlanetsRepository
import com.example.starwars.universe.NodeResolvers
import jakarta.inject.Inject
import viaduct.api.FieldValue
import viaduct.api.Resolver
import viaduct.api.grts.Planet

/**
 * Node resolver for fetching a single planet node by its ID.
 */
@Resolver
class PlanetNodeResolver
    @Inject
    constructor(
        private val planetsRepository: PlanetsRepository
    ) : NodeResolvers.Planet() {
        /**
         * Resolves planets node based on the provided context.
         */
        override suspend fun batchResolve(contexts: List<Context>): List<FieldValue<Planet>> {
            // Extract all unique planet IDs from the contexts
            val planetId = contexts.map { it.id.internalID }

            // Perform a single batch query to get film counts for all planets
            // We only compute one time for each planet, despite multiple requests
            val planets = planetId.mapNotNull {
                planetsRepository.findById(it)
            }

            // For each context gets the planet ID and map to the viaduct object
            return contexts.map { ctx ->
                val planetId = ctx.id.internalID
                planets.firstOrNull { it.id == planetId }?.let {
                    FieldValue.ofValue(
                        PlanetBuilder(ctx).build(it)
                    )
                } ?: FieldValue.ofError(IllegalArgumentException("Planet not found: $planetId"))
            }
        }
    }
