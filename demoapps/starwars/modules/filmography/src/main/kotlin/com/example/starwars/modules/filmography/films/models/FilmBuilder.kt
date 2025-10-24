package com.example.starwars.modules.filmography.films.models

import viaduct.api.context.ExecutionContext
import viaduct.api.context.globalIDFor
import viaduct.api.grts.Film

/**
 * A builder class for constructing [Film] GraphQL objects from
 * [Film] entities.
 *
 * @property ctx The execution context used for building the GraphQL object.
 */
class FilmBuilder(private val ctx: ExecutionContext) {
    fun build(film: com.example.starwars.modules.filmography.films.models.Film): Film =
        Film.Builder(ctx)
            .id(ctx.globalIDFor<Film>(film.id))
            .title(film.title)
            .episodeID(film.episodeID)
            .director(film.director)
            .producers(film.producers)
            .releaseDate(film.releaseDate)
            .created(film.created.toString())
            .edited(film.edited.toString())
            .build()
}
