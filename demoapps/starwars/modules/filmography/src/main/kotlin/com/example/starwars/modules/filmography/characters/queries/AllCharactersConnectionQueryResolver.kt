package com.example.starwars.modules.filmography.characters.queries

import com.example.starwars.filmography.resolverbases.QueryResolvers
import com.example.starwars.modules.filmography.characters.models.CharacterBuilder
import com.example.starwars.modules.filmography.characters.models.CharacterRepository
import jakarta.inject.Inject
import viaduct.api.Resolver
import viaduct.api.grts.CharactersConnection
import viaduct.apiannotations.ExperimentalApi

/**
 * Connection resolver for the `allCharactersConnection` query.
 *
 * Demonstrates Relay-style cursor pagination using Viaduct's [CharactersConnection] type
 * and [ConnectionBuilder] utilities. Supports forward pagination via `first` / `after`
 * arguments that are automatically mapped to offset-based cursors.
 *
 * Example query:
 * ```graphql
 * query {
 *   allCharactersConnection(first: 5) {
 *     edges {
 *       cursor
 *       node { id name }
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
class AllCharactersConnectionQueryResolver
    @Inject
    constructor(
        private val characterRepository: CharacterRepository
    ) : QueryResolvers.AllCharactersConnection() {
        override suspend fun resolve(ctx: Context): CharactersConnection? {
            val allCharacters = characterRepository.findAll()
            return CharactersConnection.Builder(ctx)
                .fromList(allCharacters) { CharacterBuilder(ctx).build(it) }
                .build()
        }
    }
