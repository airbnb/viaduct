package com.example.starwars.modules.filmography.characters.mutations

import com.example.starwars.common.SecurityAccessContext
import com.example.starwars.filmography.resolverbases.MutationResolvers
import com.example.starwars.modules.filmography.characters.models.CharacterFilmsRepository
import com.example.starwars.modules.filmography.characters.models.CharacterRepository
import com.example.starwars.modules.filmography.films.models.FilmCharactersRepository
import jakarta.inject.Inject
import viaduct.api.Resolver

/**
 * Mutation resolvers for the Star Wars GraphQL API.
 *
 * The Mutation type demonstrates the @scope directive which restricts schema access
 * to specific tenants or contexts. All resolvers here are scoped to "starwars".
 */
@Resolver
class DeleteCharacterMutation
    @Inject
    constructor(
        private val characterRepository: CharacterRepository,
        private val characterFilmsRepository: CharacterFilmsRepository,
        private val filmCharactersRepository: FilmCharactersRepository,
        private val securityAccessService: SecurityAccessContext
    ) : MutationResolvers.DeleteCharacter() {
        override suspend fun resolve(ctx: Context): Boolean? =
            securityAccessService.validateAccess {
                val id = ctx.arguments.id

                // Delete character, returns false if character not found
                if (!characterRepository.delete(id.internalID)) {
                    throw IllegalArgumentException("Character with ID ${id.internalID} not found")
                }

                // If the deletion was successful, remove character from all films
                filmCharactersRepository.removeCharacter(id.internalID)
                characterFilmsRepository.removeCharacter(id.internalID)

                true
            }
    }
