package com.example.starwars.modules.filmography.films.models;

import viaduct.java.api.context.ExecutionContext;
import viaduct.java.api.reflect.Type;

/** Maps film entities to generated GraphQL runtime types. */
public final class FilmBuilder {
  private final ExecutionContext context;

  public FilmBuilder(ExecutionContext context) {
    this.context = context;
  }

  public viaduct.java.grts.Film build(Film film) {
    return viaduct.java.grts.Film.builder(context)
        .id(context.globalIDFor(Type.ofClass(viaduct.java.grts.Film.class), film.id()))
        .title(film.title())
        .episodeID(film.episodeID())
        .director(film.director())
        .producers(film.producers())
        .releaseDate(film.releaseDate())
        .created(film.created().toString())
        .edited(film.edited().toString())
        .build();
  }
}
