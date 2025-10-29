package com.example.starwars.modules.universe.species.resolvers

import com.example.starwars.modules.universe.species.models.SpeciesRepository
import com.example.starwars.universe.resolverbases.SpeciesResolvers
import jakarta.inject.Inject
import viaduct.api.Resolver

/**
 * Resolver for `specialAbilities` field in Species.
 *
 * Returns a list of special abilities of the species, or null if none exist.
 */
@Resolver("id")
class SpeciesSpecialAbilitiesResolver
    @Inject
    constructor(
        private val speciesRepository: SpeciesRepository
    ) : SpeciesResolvers.SpecialAbilities() {
        override suspend fun resolve(ctx: Context): List<String?>? {
            val speciesGrt = ctx.objectValue
            val speciesId = speciesGrt.getId().internalID
            val species = speciesRepository.findById(speciesId)

            return species?.extrasData?.specialAbilities
        }
    }
