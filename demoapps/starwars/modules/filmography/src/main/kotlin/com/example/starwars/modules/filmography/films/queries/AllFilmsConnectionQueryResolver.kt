package com.example.starwars.modules.filmography.films.queries

import com.example.starwars.filmography.resolverbases.QueryResolvers
import com.example.starwars.modules.filmography.films.models.FilmBuilder
import com.example.starwars.modules.filmography.films.models.FilmsRepository
import jakarta.inject.Inject
import viaduct.api.Resolver
import viaduct.api.grts.FilmsConnection
import viaduct.apiannotations.ExperimentalApi

/**
 * Connection resolver for the `allFilmsConnection` query.
 *
 * Demonstrates Relay-style cursor pagination using Viaduct's [FilmsConnection] type
 * and [ConnectionBuilder] utilities. Supports forward pagination via `first` / `after`
 * arguments. Also exposes `totalCount` for the complete number of films regardless
 * of the current page.
 *
 * Example query:
 * ```graphql
 * query {
 *   allFilmsConnection(first: 3) {
 *     totalCount
 *     edges {
 *       cursor
 *       node { id title episodeID }
 *     }
 *     pageInfo {
 *       hasNextPage
 *       endCursor
 *     }
 *   }
 * }
 * ```
 *
 * To page forward, pass the `endCursor` from a previous response as the `after` argument.
 */
@OptIn(ExperimentalApi::class)
@Resolver
class AllFilmsConnectionQueryResolver
    @Inject
    constructor(
        private val filmsRepository: FilmsRepository,
    ) : QueryResolvers.AllFilmsConnection() {
        override suspend fun resolve(ctx: Context): FilmsConnection? {
            val allFilms = filmsRepository.getAllFilms()
            return FilmsConnection.Builder(ctx)
                .totalCount(allFilms.size)
                .fromList(allFilms) { FilmBuilder(ctx).build(it) }
                .build()
        }
    }
