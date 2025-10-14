package viaduct.demoapp.characters.viaduct.mutations

import viaduct.api.Resolver
import viaduct.api.grts.Character
import viaduct.demoapp.characters.models.repository.CharacterRepository
import viaduct.demoapp.characters.viaduct.mappers.CharacterBuilder
import viaduct.demoapp.filmography.resolverbases.MutationResolvers
import viaduct.demoapp.starwars.data.StarWarsData

/**
 * Mutation resolvers for the Star Wars GraphQL API.
 *
 * The Mutation type demonstrates the @scope directive which restricts schema access
 * to specific tenants or contexts. All resolvers here are scoped to "starwars".
 */
// tag::create_example[23] Example of a mutation resolver with scoped access
@Resolver
class CreateCharacterResolvers : MutationResolvers.CreateCharacter() {
    override suspend fun resolve(ctx: Context): Character {
        val input = ctx.arguments.input
        val homeworldId = input.inputData["homeworldId"]?.toString()

        // Validate homeworld and species existence
        // TODO: Obtain homeworld and species from Viaduct once supported
        if (homeworldId == null || StarWarsData.planets.none { it.id == homeworldId }) {
            throw IllegalArgumentException("Planet with ID $homeworldId not found")
        }

        val speciesId = input.inputData["speciesId"]?.toString()
        if (speciesId == null || StarWarsData.species.none { it.id == speciesId }) {
            throw IllegalArgumentException("Species with ID $speciesId not found")
        }

        // Create and store the new character
        val character = CharacterRepository.add(
            viaduct.demoapp.characters.models.entities.Character(
                id = "",
                name = input.name,
                birthYear = input.birthYear,
                eyeColor = input.eyeColor,
                gender = input.gender,
                hairColor = input.hairColor,
                height = input.height,
                mass = input.mass?.toFloat(),
                homeworldId = homeworldId,
                speciesId = speciesId,
            )
        )

        // Build and return the GraphQL Character object from the created entity
        return CharacterBuilder(ctx).build(character)
    }
}
