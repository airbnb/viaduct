package com.example.starwars.modules.filmography.films.resolvers

import com.example.starwars.filmography.NodeResolvers
import com.example.starwars.modules.filmography.films.models.FilmBuilder
import com.example.starwars.modules.filmography.films.models.FilmsRepository
import jakarta.inject.Inject
import viaduct.api.Resolver

/**
 * Node resolver for the Film type in the Star Wars GraphQL API.
 *
 * This resolver handles fetching a Film by its global ID.
 */
// tag::node_resolver_example[10] Example of a node resolver
@Resolver
class FilmNodeResolver
    @Inject
    constructor(
        private val filmsRepository: FilmsRepository,
    ) : NodeResolvers.Film() {
        override suspend fun resolve(ctx: Context): viaduct.api.grts.Film {
            val filmId = ctx.id.internalID

            val film = filmsRepository.findFilmById(filmId)
                ?: throw IllegalArgumentException("Film with ID $filmId not found")

            return FilmBuilder(ctx).build(film)
        }
    }
