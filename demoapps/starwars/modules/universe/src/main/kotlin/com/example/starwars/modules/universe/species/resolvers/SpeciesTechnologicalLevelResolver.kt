package com.example.starwars.modules.universe.species.resolvers

import com.example.starwars.modules.universe.species.models.SpeciesRepository
import com.example.starwars.universe.resolverbases.SpeciesResolvers
import jakarta.inject.Inject
import viaduct.api.Resolver

/**
 * Resolver for `technologicalLevel` field in Species.
 *
 * Returns the technological level of the species, or null if none exists.
 */
@Resolver("id")
class SpeciesTechnologicalLevelResolver
    @Inject
    constructor(
        private val speciesRepository: SpeciesRepository
    ) : SpeciesResolvers.TechnologicalLevel() {
        override suspend fun resolve(ctx: Context): String? {
            val speciesGrt = ctx.objectValue
            val speciesId = speciesGrt.getId().internalID
            val species = speciesRepository.findById(speciesId)

            return species?.extrasData?.technologicalLevel
        }
    }
