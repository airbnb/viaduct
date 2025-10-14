package viaduct.demoapp.films.viaduct.fieldresolvers

import viaduct.api.Resolver
import viaduct.demoapp.filmography.resolverbases.FilmResolvers

/**
 * Example of a computed field resolver in the Film type.
 *
 * This resolver computes a summary string that includes the film title, episode ID, and director.
 *
 * @resolver("title episodeID director"): Fragment syntax for accessing multiple fields
 */
// tag::resolver_example[9] Example of a computed field resolver
@Resolver("title episodeID director")
class SummaryResolver : FilmResolvers.Summary() {
    override suspend fun resolve(ctx: Context): String? {
        // Access the source Film from the context
        val film = ctx.objectValue
        return "Episode ${film.getEpisodeID()}: ${film.getTitle()} (Directed by ${film.getDirector()})"
    }
}
