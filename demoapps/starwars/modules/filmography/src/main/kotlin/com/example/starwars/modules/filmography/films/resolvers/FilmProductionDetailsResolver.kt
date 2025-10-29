package com.example.starwars.modules.filmography.films.resolvers

import com.example.starwars.filmography.resolverbases.FilmResolvers
import jakarta.inject.Inject
import viaduct.api.Resolver

/**
 * Example of a computed field resolver in the Film type.
 *
 * This resolver computes a summary string that includes the film title, director, producers, and release date.
 *
 * @resolver("fragment _ on Film { title director producers releaseDate }"): Fragment syntax for accessing multiple fields
 */
@Resolver(
    """
    fragment _ on Film {
        title
        director
        producers
        releaseDate
    }
    """
)
class FilmProductionDetailsResolver
    @Inject
    constructor() : FilmResolvers.ProductionDetails() {
        override suspend fun resolve(ctx: Context): String? {
            // Access the source Film from the context
            val film = ctx.objectValue
            val producerList = film.getProducers()?.filterNotNull()?.joinToString(", ") ?: "Unknown producers"
            return "${film.getTitle()} was released on ${film.getReleaseDate()}, directed by ${film.getDirector()} and produced by $producerList"
        }
    }
