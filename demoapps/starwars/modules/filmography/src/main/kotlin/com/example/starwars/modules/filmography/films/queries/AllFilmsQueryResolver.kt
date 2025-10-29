package com.example.starwars.modules.filmography.films.queries

import com.example.starwars.filmography.resolverbases.QueryResolvers
import com.example.starwars.modules.filmography.films.models.FilmBuilder
import com.example.starwars.modules.filmography.films.models.FilmsRepository
import jakarta.inject.Inject
import viaduct.api.Resolver
import viaduct.api.grts.Film

private const val DEFAULT_PAGE_SIZE = 10

/**
 * Resolver for the `allFilms` query in the Star Wars GraphQL API.
 *
 * This resolver fetches a list of films, limited by the provided argument or a default page size.
 */
@Resolver
class AllFilmsQueryResolver
    @Inject
    constructor(
        private val filmsRepository: FilmsRepository,
    ) : QueryResolvers.AllFilms() {
        override suspend fun resolve(ctx: Context): List<Film?>? {
            val limit = ctx.arguments.limit ?: DEFAULT_PAGE_SIZE
            val films = filmsRepository.getAllFilms().take(limit)

            // Convert StarWarsData.Film objects to Film objects
            return films.map { FilmBuilder(ctx).build(it) }
        }
    }
