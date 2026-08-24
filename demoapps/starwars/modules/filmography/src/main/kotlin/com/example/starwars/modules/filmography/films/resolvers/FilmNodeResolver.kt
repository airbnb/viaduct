package com.example.starwars.modules.filmography.films.resolvers

import com.example.starwars.filmography.resolverbases.NodeResolvers
import com.example.starwars.modules.filmography.films.models.FilmsRepository
import io.micronaut.context.annotation.Prototype
import jakarta.inject.Inject
import viaduct.api.context.globalIDFor
import viaduct.api.grts.Film as GRTFilm
import viaduct.api.resolver.Resolver

/**
 * Node resolver for the Film type in the Star Wars GraphQL API.
 *
 * This resolver handles fetching a Film by its global ID.
 */
// tag::node_resolver_example[15] Example of a node resolver
@Resolver
@Prototype
class FilmNodeResolver
    @Inject
    constructor(
        private val filmsRepository: FilmsRepository,
    ) : NodeResolvers.Film() {
        override suspend fun resolve(ctx: Context): GRTFilm {
            val filmId = ctx.id.internalID

            val film = filmsRepository.findFilmById(filmId)
                ?: throw IllegalArgumentException("Film with ID $filmId not found")
            val selections = ctx.selections()

            return GRTFilm.of(ctx) {
                id(ctx.globalIDFor<GRTFilm>(film.id))
                if (selections.contains(GRTFilm.Fields.title)) title(film.title)
                if (selections.contains(GRTFilm.Fields.episodeID)) episodeID(film.episodeID)
                if (selections.contains(GRTFilm.Fields.openingCrawl)) openingCrawl(film.openingCrawl)
                if (selections.contains(GRTFilm.Fields.director)) director(film.director)
                if (selections.contains(GRTFilm.Fields.producers)) producers(film.producers)
                if (selections.contains(GRTFilm.Fields.releaseDate)) releaseDate(film.releaseDate)
                if (selections.contains(GRTFilm.Fields.created)) created(film.created.toString())
                if (selections.contains(GRTFilm.Fields.edited)) edited(film.edited.toString())
            }
        }
    }
