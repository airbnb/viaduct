package viaduct.demoapp.starwars.builders

import viaduct.api.context.ExecutionContext
import viaduct.api.grts.Film
import viaduct.demoapp.starwars.data.StarWarsData

class FilmBuilder(private val ctx: ExecutionContext) {
    fun build(film: StarWarsData.Film): Film =
        Film.Builder(ctx)
            .id(ctx.globalIDFor(Film.Reflection, film.id))
            .title(film.title)
            .episodeID(film.episodeID)
            .director(film.director)
            .producers(film.producers)
            .releaseDate(film.releaseDate)
            .created(film.created.toString())
            .edited(film.edited.toString())
            .build()
}
