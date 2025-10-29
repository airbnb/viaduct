package com.example.starwars.modules.universe.planets.queries

import com.example.starwars.modules.universe.planets.models.PlanetsResidentsRepository
import com.example.starwars.universe.resolverbases.PlanetResolvers
import jakarta.inject.Inject
import viaduct.api.Resolver
import viaduct.api.context.nodeFor
import viaduct.api.grts.Character

/**
 * Resolver to fetch the residents of a planet.
 * It retrieves characters whose homeworld matches the planet's ID.
 */
@Resolver("id")
class PlanetResidentsQueryResolver
    @Inject
    constructor(
        private val planetsResidentsRepository: PlanetsResidentsRepository
    ) : PlanetResolvers.Residents() {
        /**
         * Resolves the list of residents for a given planet based on the context.
         *
         * @param ctx The context containing the planet information.
         * @return A list of viaduct `Character` objects who are residents of the planet, or null if none are found.
         */
        override suspend fun resolve(ctx: Context): List<Character>? {
            // Related Planet ID is stored in the ctx object value.
            val planetId = ctx.objectValue.getId().internalID

            // Fetch residents associated with the planet from the repository.
            val residents = planetsResidentsRepository.findResidentsByPlanetId(planetId)

            return residents.map {
                // Request Viaduct to resolve the Character node using the global ID.
                ctx.nodeFor<Character>(it.characterId)
            }
        }
    }
