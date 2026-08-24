package com.example.starwars.modules.filmography.films.resolvers

import com.example.starwars.filmography.resolverbases.FilmResolvers
import io.micronaut.context.annotation.Prototype
import viaduct.api.resolver.Resolver

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
@Prototype
class FilmProductionDetailsResolver : FilmResolvers.ProductionDetails() {
    override suspend fun resolve(ctx: Context): String? {
        // Access the source Film from the context
        val film = ctx.getObjectValue()
        val producerList = film.getProducersOrThrow()?.filterNotNull()?.joinToString(", ") ?: "Unknown producers"
        return "${film.getTitleOrThrow()} was released on ${film.getReleaseDateOrThrow()}, directed by ${film.getDirectorOrThrow()} and produced by $producerList"
    }
}
