package com.example.starwars.modules.filmography.films.mutations

import com.example.starwars.common.SecurityAccessContext
import com.example.starwars.filmography.resolverbases.MutationResolvers
import com.example.starwars.modules.filmography.characters.models.CharacterBuilder
import com.example.starwars.modules.filmography.characters.models.CharacterFilmsRepository
import com.example.starwars.modules.filmography.characters.models.CharacterRepository
import com.example.starwars.modules.filmography.films.models.FilmBuilder
import com.example.starwars.modules.filmography.films.models.FilmCharactersRepository
import com.example.starwars.modules.filmography.films.models.FilmsRepository
import jakarta.inject.Inject
import viaduct.api.Resolver
import viaduct.api.grts.AddCharacterToFilmPayload

/**
 * Mutation resolvers for the Star Wars GraphQL API.
 *
 * The Mutation type demonstrates the @scope directive which restricts schema access
 * to specific tenants or contexts. All resolvers here are scoped to "starwars".
 */
@Resolver
class AddCharacterToFilmMutation
    @Inject
    constructor(
        private val characterFilmsRepository: CharacterFilmsRepository,
        private val filmCharactersRepository: FilmCharactersRepository,
        private val filmsRepository: FilmsRepository,
        private val characterRepository: CharacterRepository,
        private val securityAccessService: SecurityAccessContext
    ) : MutationResolvers.AddCharacterToFilm() {
        override suspend fun resolve(ctx: Context): AddCharacterToFilmPayload? =
            securityAccessService.validateAccess {
                // Extract input arguments
                val input = ctx.arguments.input
                val filmId = input.filmId.internalID
                val characterId = input.characterId?.internalID ?: throw IllegalArgumentException("Character ID is required")

                // Early validation to ensure both character and film exist
                characterRepository.findById(characterId)
                    ?: throw IllegalArgumentException("Character with ID $characterId not found")

                filmsRepository.findFilmById(filmId)
                    ?: throw IllegalArgumentException("Film with ID $filmId not found")

                // Add character to film in both repositories to maintain consistency
                characterFilmsRepository.addCharacterToFilm(characterId, filmId)
                filmCharactersRepository.addCharacterToFilm(filmId, characterId)

                // Fetch updated entities
                val character = characterRepository.findById(characterId)
                    ?: throw IllegalArgumentException("Character with ID $characterId not found")

                val film = filmsRepository.findFilmById(filmId)
                    ?: throw IllegalArgumentException("Film with ID $filmId not found")

                // From updated entities, build GraphQL objects to build the payload
                val filmGrt = FilmBuilder(ctx).build(film)
                val characterGrt = CharacterBuilder(ctx).build(character)

                AddCharacterToFilmPayload.Builder(ctx)
                    .film(filmGrt)
                    .character(characterGrt)
                    .build()
            }
    }
