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
// tag::create_example[29] Example of a mutation resolver with scoped access
@Resolver
class CreateCharacterMutation
    @Inject
    constructor(
        private val characterRepository: CharacterRepository,
        private val securityAccessContext: SecurityAccessContext
    ) : MutationResolvers.CreateCharacter() {
        override suspend fun resolve(ctx: Context): Character =
            securityAccessContext.validateAccess {
                val input = ctx.arguments.input
                val homeworldId = input.homeworldId
                val speciesId = input.speciesId

                // TODO: Validate homeworld and species are valid ids

                // Create and store the new character
                val character = characterRepository.add(
                    com.example.starwars.modules.filmography.characters.models.Character(
                        id = "",
                        name = input.name,
                        birthYear = input.birthYear,
                        eyeColor = input.eyeColor,
                        gender = input.gender,
                        hairColor = input.hairColor,
                        height = input.height,
                        mass = input.mass?.toFloat(),
                        homeworldId = homeworldId?.internalID,
                        speciesId = speciesId?.internalID,
                    )
                )

                // Build and return the GraphQL Character object from the created entity
                CharacterBuilder(ctx).build(character)
            }
    }
