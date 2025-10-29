package com.example.starwars.modules.filmography.films.resolvers

import com.example.starwars.filmography.resolverbases.FilmResolvers
import com.example.starwars.modules.filmography.films.models.FilmCharactersRepository
import jakarta.inject.Inject
import viaduct.api.Resolver

/**
 * Example of a computed field resolver in the Film type.
 *
 * This resolver computes a summary string that includes the film title and the count of main characters
 *
 * @resolver("fragment _ on Film { id title }"): Fragment syntax for accessing film title
 */
@Resolver(
    """
    fragment _ on Film {
        id
        title
    }
    """
)
class FilmCharacterCountSummaryResolver
    @Inject
    constructor(
        val filmCharactersRepository: FilmCharactersRepository
    ) : FilmResolvers.CharacterCountSummary() {
        override suspend fun resolve(ctx: Context): String? {
            // Access the source Film from the context
            val film = ctx.objectValue
            val filmId = film.getId().internalID

            // Access character count from the relationship data
            val characterCount = filmCharactersRepository.findCharactersByFilmId(filmId).size

            return "${film.getTitle()} features $characterCount main characters"
        }
    }
