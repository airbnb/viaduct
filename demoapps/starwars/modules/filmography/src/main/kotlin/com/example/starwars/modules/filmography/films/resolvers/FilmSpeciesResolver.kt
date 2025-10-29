package com.example.starwars.modules.filmography.films.resolvers

import com.example.starwars.filmography.resolverbases.FilmResolvers
import com.example.starwars.modules.filmography.characters.models.CharacterRepository
import com.example.starwars.modules.filmography.films.models.FilmCharactersRepository
import jakarta.inject.Inject
import viaduct.api.Resolver
import viaduct.api.context.globalIDFor
import viaduct.api.grts.Species

/**
 * Example of a relationship field resolver in the Film type.
 *
 * This resolver fetches the list of unique species of main characters appearing in a film.
 *
 * @resolver("fragment _ on Film { id }"): Fragment syntax for accessing film ID
 */
@Resolver("id")
class FilmSpeciesResolver
    @Inject
    constructor(
        private val characterRepository: CharacterRepository,
        private val filmCharactersRepository: FilmCharactersRepository
    ) : FilmResolvers.Species() {
        override suspend fun resolve(ctx: Context): List<Species?>? {
            val filmId = ctx.objectValue.getId().internalID

            val characterIds = filmCharactersRepository.findCharactersByFilmId(filmId)

            val speciesIds = characterIds.mapNotNull { characterRepository.findById(it)?.speciesId }.toSet()

            return speciesIds.map {
                val globalId = ctx.globalIDFor<Species>(it)
                ctx.nodeFor(globalId)
            }
        }
    }
