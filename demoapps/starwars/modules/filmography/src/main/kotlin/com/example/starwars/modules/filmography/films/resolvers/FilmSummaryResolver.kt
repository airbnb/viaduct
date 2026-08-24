package com.example.starwars.modules.filmography.films.resolvers

import com.example.starwars.filmography.resolverbases.FilmResolvers
import io.micronaut.context.annotation.Prototype
import viaduct.api.resolver.Resolver

/**
 * Example of a computed field resolver in the Film type.
 *
 * This resolver computes a summary string that includes the film title, episode ID, and director.
 *
 * @resolver("title episodeID director"): Fragment syntax for accessing multiple fields
 */
// tag::resolver_example[10] Example of a computed field resolver
@Resolver("title episodeID director")
@Prototype
class FilmSummaryResolver : FilmResolvers.Summary() {
    override suspend fun resolve(ctx: Context): String? {
        // Access the source Film from the context
        val film = ctx.getObjectValue()
        return "Episode ${film.getEpisodeIDOrThrow()}: ${film.getTitleOrThrow()} (Directed by ${film.getDirectorOrThrow()})"
    }
}
