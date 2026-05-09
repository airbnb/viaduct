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
import viaduct.api.grts.AddCharacterToFilmPayload
import viaduct.api.resolver.Resolver

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
        override suspend fun resolve(ctx: Context): AddCharacterToFilmPayload =
            securityAccessService.validateAccess {
                val input = ctx.arguments.input
                val filmId = input.filmId.internalID
                val characterId = input.characterId?.internalID
                    ?: throw IllegalArgumentException("Character ID is required")

                characterFilmsRepository.addCharacterToFilm(characterId, filmId)
                filmCharactersRepository.addCharacterToFilm(filmId, characterId)

                AddCharacterToFilmPayload.of(ctx) {
                    val film = filmsRepository.findFilmById(filmId)
                        ?: throw IllegalArgumentException("Film with ID $filmId not found")
                    val character = characterRepository.findById(characterId)
                        ?: throw IllegalArgumentException("Character with ID $characterId not found")

                    val filmGrt = FilmBuilder(ctx).build(film)
                    val characterGrt = CharacterBuilder(ctx).build(character)

                    film(filmGrt)
                    character(characterGrt)
                }
            }
    }
