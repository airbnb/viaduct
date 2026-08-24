package com.example.starwars.modules.filmography.films.resolvers

import com.example.starwars.filmography.resolverbases.FilmResolvers
import io.micronaut.context.annotation.Prototype
import viaduct.api.grts.FilmProductionDetails
import viaduct.api.resolver.Resolver

/**
 * Selective field resolver that only materializes requested production details.
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
    override suspend fun resolve(ctx: Context): FilmProductionDetails? {
        val film = ctx.getObjectValue()
        val selections = ctx.selections()

        return FilmProductionDetails.of(ctx) {
            if (selections.contains(FilmProductionDetails.Fields.title)) title(film.getTitleOrThrow())
            if (selections.contains(FilmProductionDetails.Fields.director)) director(film.getDirectorOrThrow())
            if (selections.contains(FilmProductionDetails.Fields.producers)) producers(film.getProducersOrThrow())
            if (selections.contains(FilmProductionDetails.Fields.releaseDate)) releaseDate(film.getReleaseDateOrThrow())
        }
    }
}
