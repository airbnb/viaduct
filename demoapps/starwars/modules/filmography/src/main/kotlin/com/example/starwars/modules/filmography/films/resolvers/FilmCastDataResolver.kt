package com.example.starwars.modules.filmography.films.resolvers

import com.example.starwars.filmography.resolverbases.FilmResolvers
import com.example.starwars.modules.filmography.films.models.FilmCastData
import com.example.starwars.modules.filmography.films.models.FilmCharactersRepository
import io.micronaut.context.annotation.Prototype
import jakarta.inject.Inject
import viaduct.api.resolver.Resolver

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
// tag::backing_data_resolver[12]
@Resolver(objectValueFragment = "fragment _ on Film { id }")
@Prototype
class FilmCastDataResolver
    @Inject
    constructor(
        private val filmCharactersRepository: FilmCharactersRepository
    ) : FilmResolvers.CastData() {
        override suspend fun resolve(ctx: Context): FilmCastData {
            val filmId = ctx.getObjectValue().getId().internalID
            val characterIds = filmCharactersRepository.findCharactersByFilmId(filmId)
            return FilmCastData(characterIds)
        }
    }
