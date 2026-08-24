package com.example.starwars.modules.filmography.films.resolvers

import com.example.starwars.filmography.resolverbases.FilmResolvers
import com.example.starwars.modules.filmography.characters.models.CharacterRepository
import com.example.starwars.modules.filmography.films.models.FilmCharactersRepository
import io.micronaut.context.annotation.Prototype
import jakarta.inject.Inject
import viaduct.api.context.globalIDFor
import viaduct.api.grts.Species
import viaduct.api.resolver.Resolver

/**
 * Example of a relationship field resolver in the Film type.
 *
 * This resolver fetches the list of unique species of main characters appearing in a film.
 *
 * @resolver("fragment _ on Film { id }"): Fragment syntax for accessing film ID
 */
@Resolver("id")
@Prototype
class FilmSpeciesResolver
    @Inject
    constructor(
        private val characterRepository: CharacterRepository,
        private val filmCharactersRepository: FilmCharactersRepository
    ) : FilmResolvers.Species() {
        override suspend fun resolve(ctx: Context): List<Species?>? {
            val filmId = ctx.getObjectValue().getIdOrThrow().internalID

            val characterIds = filmCharactersRepository.findCharactersByFilmId(filmId)

            val speciesIds = characterIds.mapNotNull { characterRepository.findById(it)?.speciesId }.toSet()

            return speciesIds.map {
                val globalId = ctx.globalIDFor<Species>(it)
                ctx.nodeRef(globalId)
            }
        }
    }
