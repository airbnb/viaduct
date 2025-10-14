package viaduct.demoapp.characters.viaduct.mutations

import viaduct.api.Resolver
import viaduct.api.grts.Character
import viaduct.demoapp.characters.models.repository.CharacterRepository
import viaduct.demoapp.characters.viaduct.mappers.CharacterBuilder
import viaduct.demoapp.filmography.resolverbases.MutationResolvers

/**
 * Mutation resolvers for the Star Wars GraphQL API.
 *
 * The Mutation type demonstrates the @scope directive which restricts schema access
 * to specific tenants or contexts. All resolvers here are scoped to "starwars".
 */
// tag::update-character-name-resolver[20] Example of mutation resolver
@Resolver
class UpdateCharacterNameResolvers : MutationResolvers.UpdateCharacterName() {
    override suspend fun resolve(ctx: Context): Character? {
        val id = ctx.arguments.id
        val name = ctx.arguments.name

        // Fetch existing character
        val character = CharacterRepository.findById(id.internalID)
            ?: throw IllegalArgumentException("Character with ID ${id.internalID} not found")

        // Update character's name
        val updatedCharacter = character.copy(name = name)

        val newCharacter = CharacterRepository.update(updatedCharacter)

        // Return the updated character as a GraphQL object
        return CharacterBuilder(ctx).build(newCharacter)
    }
}
