package com.example.starwars.modules.universe.species.resolvers

import com.example.starwars.modules.universe.species.models.SpeciesRepository
import com.example.starwars.universe.resolverbases.SpeciesResolvers
import io.micronaut.context.annotation.Prototype
import jakarta.inject.Inject
import viaduct.api.resolver.Resolver

/**
 * Resolver for `culturalNotes` field in Species.
 *
 * Returns any additional cultural information about the species, or null if none exists.
 */
@Resolver("id")
@Prototype
class SpeciesCulturalNotesResolver
    @Inject
    constructor(
        private val speciesRepository: SpeciesRepository
    ) : SpeciesResolvers.CulturalNotes() {
        override suspend fun resolve(ctx: Context): String? {
            val speciesGrt = ctx.getObjectValue()
            val speciesId = speciesGrt.getId().internalID
            val species = speciesRepository.findById(speciesId)

            return species?.extrasData?.culturalNotes
        }
    }
