package com.example.starwars.modules.filmography.films.resolvers

import com.example.starwars.filmography.resolverbases.FilmResolvers
import com.example.starwars.modules.filmography.characters.models.CharacterRepository
import com.example.starwars.modules.filmography.films.models.FilmCharactersRepository
import jakarta.inject.Inject
import viaduct.api.context.nodeRef
import viaduct.api.grts.Planet
import viaduct.api.resolver.Resolver

/**
 * Example of a relationship field resolver in the Film type.
 *
 * This resolver fetches the list of unique homeworld planets of main characters appearing in a film.
 *
 * @resolver("fragment _ on Film { id }"): Fragment syntax for accessing film ID
 */
@Resolver("id")
class FilmPlanetsResolver
    @Inject
    constructor(
        private val characterRepository: CharacterRepository,
        private val filmCharactersRepository: FilmCharactersRepository
    ) : FilmResolvers.Planets() {
        override suspend fun resolve(ctx: Context): List<Planet?>? {
            val filmId = ctx.getObjectValue().getId().internalID

            val characterIds = filmCharactersRepository.findCharactersByFilmId(filmId)

            val planetIds = characterIds.mapNotNull { characterRepository.findById(it)?.homeworldId }.toSet()

            return planetIds.map {
                ctx.nodeRef<Planet>(it)
            }
        }
    }
