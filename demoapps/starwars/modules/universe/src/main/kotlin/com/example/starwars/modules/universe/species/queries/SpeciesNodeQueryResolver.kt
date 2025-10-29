package com.example.starwars.modules.universe.species.queries

import com.example.starwars.modules.universe.species.models.SpeciesBuilder
import com.example.starwars.modules.universe.species.models.SpeciesRepository
import com.example.starwars.universe.NodeResolvers
import jakarta.inject.Inject
import viaduct.api.FieldValue
import viaduct.api.Resolver
import viaduct.api.grts.Species

/**
 * Node resolver for the Species type in the Star Wars GraphQL API.
 *
 * This resolver handles fetching a Species by its global ID.
 */
@Resolver
class SpeciesNodeQueryResolver
    @Inject
    constructor(
        private val speciesRepository: SpeciesRepository
    ) : NodeResolvers.Species() {
        override suspend fun batchResolve(contexts: List<Context>): List<FieldValue<Species>> {
            // Extract all unique species IDs from the contexts
            val specieId = contexts.map { it.id.internalID }

            // Perform a single batch query to get film counts for all species
            // We only compute one time for each specie, despite multiple requests
            val species = specieId.mapNotNull {
                speciesRepository.findById(it)
            }

            // For each context gets the specie ID and map to the viaduct object
            return contexts.map { ctx ->
                val specieId = ctx.id.internalID
                species.firstOrNull { it.id == specieId }?.let {
                    FieldValue.ofValue(
                        SpeciesBuilder(ctx).build(it)
                    )
                } ?: FieldValue.ofError(IllegalArgumentException("Specie not found: $specieId"))
            }
        }
    }
