package com.example.starwars.modules.filmography.characters.queries

import com.example.starwars.filmography.resolverbases.QueryResolvers
import com.example.starwars.modules.filmography.characters.models.CharacterBuilder
import com.example.starwars.modules.filmography.characters.models.CharacterRepository
import jakarta.inject.Inject
import viaduct.api.Resolver
import viaduct.api.grts.Character

private const val DEFAULT_PAGE_SIZE = 10

/**
 * AllCharacters Query resolver for the Star Wars GraphQL API.
 */
// tag::resolver_example[16] Example of a query resolver with pagination
@Resolver
class AllCharactersQueryResolver
    @Inject
    constructor(
        private val characterRepository: CharacterRepository
    ) : QueryResolvers.AllCharacters() {
        override suspend fun resolve(ctx: Context): List<Character?>? {
            // Fetch characters with pagination
            val limit = ctx.arguments.limit ?: DEFAULT_PAGE_SIZE
            val characters = characterRepository.findAll().take(limit)

            // Convert StarWarsData.Character objects to Character objects
            return characters.map { CharacterBuilder(ctx).build(it) }
        }
    }
