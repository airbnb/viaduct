package com.example.starwars.modules.universe.planets.queries

import com.example.starwars.modules.universe.planets.models.PlanetsFilmsRepository
import com.example.starwars.universe.resolverbases.PlanetResolvers
import jakarta.inject.Inject
import viaduct.api.Resolver
import viaduct.api.context.nodeFor
import viaduct.api.grts.Film

/**
 * Resolver to fetch films associated with a specific planet.
 */
@Resolver("id")
class PlanetFilmsQueryResolver
    @Inject
    constructor(
        private val planetsFilmsRepository: PlanetsFilmsRepository
    ) : PlanetResolvers.Films() {
        /**
         * Resolves the list of films for a given planet based on the context.
         *
         * @param ctx The context containing the planet information.
         * @return A list of `Film` objects associated with the planet, or null if none are found.
         */
        override suspend fun resolve(ctx: Context): List<Film>? {
            // Related Planet ID is stored in the ctx object value.
            val planetId = ctx.objectValue.getId().internalID

            // Fetch films associated with the planet from the repository.
            val films = planetsFilmsRepository.findFilmsByPlanetId(planetId)

            // You need to iterate the internal films to request viaduct resolve the Films.
            return films.map {
                // Request Viaduct to resolve the Film node using the global ID.
                ctx.nodeFor<Film>(it.filmId)
            }
        }
    }
