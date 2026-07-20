package com.example.starwars.modules.filmography.characters.queries

import com.example.starwars.filmography.resolverbases.QueryResolvers
import com.example.starwars.modules.filmography.characters.models.CharacterBuilder
import com.example.starwars.modules.filmography.characters.models.CharacterRepository
import io.micronaut.context.annotation.Prototype
import jakarta.inject.Inject
import viaduct.api.resolver.Resolver

/**
 * SearchCharacter Query resolver for the Star Wars GraphQL API.
 *
 * Allows searching for a character by name, ID, or birth year.
 * If multiple criteria are provided, the precedence is:
 * 1. byName: Returns the first matching character found for name searches.
 * 2. byId: Returns the character with the exact matching ID.
 * 3. byBirthYear: Returns the first matching character found for name or birth year searches.
 *
 * If no criteria are provided, returns null.
 *
 * Arguments:
 * - search: An object containing optional fields: byName (String), byId (ID), byBirthYear (String).
 */
// tag::id_of_example[24] Example of idOF usage
@Resolver
@Prototype
class SearchCharacterQueryResolver
    @Inject
    constructor(
        private val characterRepository: CharacterRepository
    ) : QueryResolvers.SearchCharacter() {
        override suspend fun resolve(ctx: Context): viaduct.api.grts.Character? {
            val search = ctx.arguments.search
            val byName = search.byName
            val byId = search.byId
            val byBirthYear = search.byBirthYear

            val character = when {
                byName != null -> characterRepository.findCharactersByName(byName).firstOrNull()
                byId != null -> characterRepository.findById(byId.internalID)
                byBirthYear != null -> characterRepository.findCharactersByYearOfBirth(byBirthYear).firstOrNull()
                else -> null
            }

            return character?.let {
                CharacterBuilder(ctx).build(character)
            }
        }
    }
