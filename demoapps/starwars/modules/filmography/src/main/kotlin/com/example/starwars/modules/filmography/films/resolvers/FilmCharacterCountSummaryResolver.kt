package com.example.starwars.modules.filmography.films.resolvers

import com.example.starwars.filmography.resolverbases.FilmResolvers
import com.example.starwars.modules.filmography.films.models.FilmCastData
import jakarta.inject.Inject
import viaduct.api.Resolver

/**
 * Computes a summary string showing how many characters appear in the film.
 *
 * Uses [FilmCastData] backing data resolved by [FilmCastDataResolver] so that
 * the `findCharactersByFilmId` call is shared with [FilmCharactersResolver].
 * When both `characterCountSummary` and `characters` are requested for the same film,
 * the repository is called once instead of twice.
 */
// tag::backing_data_consumer_2[17]
@Resolver(
    """
    fragment _ on Film {
        title
        castData
    }
    """
)
class FilmCharacterCountSummaryResolver
    @Inject
    constructor() : FilmResolvers.CharacterCountSummary() {
        override suspend fun resolve(ctx: Context): String? {
            val film = ctx.getObjectValue()
            val castData = film.get<FilmCastData>("castData", FilmCastData::class)
            return "${film.getTitle()} features ${castData.characterIds.size} main characters"
        }
    }
