package viaduct.demoapp.characters.viaduct.queryresolvers

import viaduct.api.Resolver
import viaduct.api.grts.Character
import viaduct.demoapp.characters.models.repository.CharacterRepository
import viaduct.demoapp.characters.viaduct.mappers.CharacterBuilder
import viaduct.demoapp.filmography.resolverbases.QueryResolvers

private const val DEFAULT_PAGE_SIZE = 10

/**
 * AllCharacters Query resolver for the Star Wars GraphQL API.
 */
// tag::resolver_example[16] Example of a query resolver with pagination
@Resolver
class AllCharactersResolver : QueryResolvers.AllCharacters() {
    override suspend fun resolve(ctx: Context): List<Character?>? {
        // Fetch characters with pagination
        val limit = ctx.arguments.limit ?: DEFAULT_PAGE_SIZE
        val characters = CharacterRepository.findAll().take(limit)

        // Convert StarWarsData.Character objects to Character objects
        return characters.map { CharacterBuilder(ctx).build(it) }
    }
}
