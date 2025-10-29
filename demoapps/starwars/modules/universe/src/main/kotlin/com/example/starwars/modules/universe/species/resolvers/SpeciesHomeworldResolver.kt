package com.example.starwars.modules.universe.species.resolvers

import com.example.starwars.modules.universe.species.models.SpeciesRepository
import com.example.starwars.universe.resolverbases.SpeciesResolvers
import jakarta.inject.Inject
import viaduct.api.Resolver
import viaduct.api.context.globalIDFor
import viaduct.api.grts.Planet

/**
 * Resolver for `homeworld` field in Species.
 *
 * Returns the planet that is the homeworld of this species, or null if none exists.
 */
@Resolver("id")
class SpeciesHomeworldResolver
    @Inject
    constructor(
        private val speciesRepository: SpeciesRepository
    ) : SpeciesResolvers.Homeworld() {
        override suspend fun resolve(ctx: Context): Planet? {
            val species = ctx.objectValue
            val homeWorldId = speciesRepository.findById(species.getId().internalID)?.homeworldId ?: return null

            val planetId = ctx.globalIDFor<Planet>(homeWorldId)

            return ctx.nodeFor(planetId)
        }
    }
