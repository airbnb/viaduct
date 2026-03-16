package com.example.starwars.modules.filmography.films.models

/**
 * Internal backing data holding the character IDs for a film.
 *
 * Fetched once by [FilmCastDataResolver] from [FilmCharactersRepository]
 * and shared between [FilmCharacterCountSummaryResolver] and
 * [FilmIsEnsembleCastResolver], so a single query asking for both fields
 * causes only one repository call per film.
 */
data class FilmCastData(val characterIds: List<String>)
