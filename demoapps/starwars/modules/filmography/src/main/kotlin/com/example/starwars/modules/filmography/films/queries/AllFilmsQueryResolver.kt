package com.example.starwars.modules.filmography.films.queries

import com.example.starwars.filmography.resolverbases.QueryResolvers
import com.example.starwars.modules.filmography.films.models.FilmsRepository
import io.micronaut.context.annotation.Prototype
import jakarta.inject.Inject
import viaduct.api.context.globalIDFor
import viaduct.api.grts.Film
import viaduct.api.resolver.Resolver

private const val DEFAULT_PAGE_SIZE = 10

/**
 * Resolver for the `allFilms` query in the Star Wars GraphQL API.
 *
 * This resolver fetches a list of films, limited by the provided argument or a default page size.
 */
@Resolver
@Prototype
class AllFilmsQueryResolver
    @Inject
    constructor(
        private val filmsRepository: FilmsRepository,
    ) : QueryResolvers.AllFilms() {
        override suspend fun resolve(ctx: Context): List<Film?>? {
            val limit = ctx.arguments.limit ?: DEFAULT_PAGE_SIZE

            // return references to Film nodes, to be resolved by the Film node resolver
            return filmsRepository.getAllFilms()
                .take(limit)
                .map { film ->
                    ctx.nodeRef(ctx.globalIDFor<Film>(film.id))
                }
        }
    }
