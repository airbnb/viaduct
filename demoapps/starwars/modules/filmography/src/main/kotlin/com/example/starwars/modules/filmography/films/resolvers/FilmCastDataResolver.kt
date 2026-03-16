package com.example.starwars.modules.filmography.films.resolvers

import com.example.starwars.filmography.resolverbases.FilmResolvers
import com.example.starwars.modules.filmography.films.models.FilmCastData
import com.example.starwars.modules.filmography.films.models.FilmCharactersRepository
import jakarta.inject.Inject
import viaduct.api.Resolver

/**
 * Backing-data resolver for a film's cast.
 *
 * Calls [FilmCharactersRepository.findCharactersByFilmId] once per Film object.
 * The result is stored as [FilmCastData] and shared with every other resolver
 * that declares `castData` in its `objectValueFragment` (currently
 * [FilmCharacterCountSummaryResolver] and [FilmIsEnsembleCastResolver]).
 * Viaduct guarantees this resolver runs at most once per Film, regardless of
 * how many of those fields appear in the query.
 */
@Resolver(objectValueFragment = "fragment _ on Film { id }")
class FilmCastDataResolver
    @Inject
    constructor(
        private val filmCharactersRepository: FilmCharactersRepository
    ) : FilmResolvers.CastData() {
        override suspend fun resolve(ctx: Context): FilmCastData {
            val filmId = ctx.objectValue.getId().internalID
            val characterIds = filmCharactersRepository.findCharactersByFilmId(filmId)
            return FilmCastData(characterIds)
        }
    }
