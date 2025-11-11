package com.example.starwars.modules.filmography.characters.mutations

import com.example.starwars.common.SecurityAccessContext
import com.example.starwars.filmography.resolverbases.MutationResolvers
import com.example.starwars.modules.filmography.characters.models.CharacterBuilder
import com.example.starwars.modules.filmography.characters.models.CharacterRepository
import jakarta.inject.Inject
import viaduct.api.Resolver
import viaduct.api.grts.Character

/**
 * Mutation resolvers for the Star Wars GraphQL API.
 *
 * The Mutation type demonstrates the @scope directive which restricts schema access
 * to specific tenants or contexts. All resolvers here are scoped to "starwars".
 */
// tag::update-character-name-resolver[20] Example of mutation resolver
@Resolver
class UpdateCharacterNameMutation
    @Inject
    constructor(
        private val characterRepository: CharacterRepository,
        private val securityAccessService: SecurityAccessContext
    ) : MutationResolvers.UpdateCharacterName() {
        override suspend fun resolve(ctx: Context): Character? =
            securityAccessService.validateAccess {
                val id = ctx.arguments.id
                val name = ctx.arguments.name

                // Fetch existing character
                val character = characterRepository.findById(id.internalID)
                    ?: throw IllegalArgumentException("Character with ID ${id.internalID} not found")

                // Update character's name
                val updatedCharacter = character.copy(name = name)

                val newCharacter = characterRepository.update(updatedCharacter)

                // Return the updated character as a GraphQL object
                CharacterBuilder(ctx).build(newCharacter)
            }
    }
