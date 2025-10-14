package viaduct.demoapp.characters.viaduct.queryresolvers

import viaduct.api.Resolver
import viaduct.demoapp.characters.models.repository.CharacterRepository
import viaduct.demoapp.characters.viaduct.mappers.CharacterBuilder
import viaduct.demoapp.filmography.NodeResolvers

/**
 * Node resolver for the Character type in the Star Wars GraphQL API.
 *
 * This resolver handles fetching a Character by its global ID.
 */
// tag::node_resolver_example[17] Example of a node resolver
@Resolver
class CharacterNodeResolver : NodeResolvers.Character() {
    override suspend fun resolve(ctx: Context): viaduct.api.grts.Character {
        val stringId = ctx.id.internalID

        // Fetch character by ID
        val character = CharacterRepository.findById(stringId)
            ?: throw IllegalArgumentException("Character with ID $stringId not found")

        // Convert and return the character as a GraphQL object
        return CharacterBuilder(ctx).build(character)
    }
}
