package com.example.starwars.modules.filmography.films.resolvers;

import com.example.starwars.filmography.resolverbases.NodeResolvers;
import com.example.starwars.modules.filmography.films.models.FilmBuilder;
import com.example.starwars.modules.filmography.films.models.FilmsRepository;
import io.micronaut.context.annotation.Prototype;
import jakarta.inject.Inject;
import java.util.concurrent.CompletableFuture;
import viaduct.java.api.annotations.Resolver;
import viaduct.java.grts.Film;

/** Node resolver for films. */
@Resolver
@Prototype
public final class FilmNodeResolver extends NodeResolvers.Film {
  private final FilmsRepository filmsRepository;

  @Inject
  public FilmNodeResolver(FilmsRepository filmsRepository) {
    this.filmsRepository = filmsRepository;
  }

  @Override
  public CompletableFuture<Film> resolve(Context context) {
    String filmId = context.getId().getInternalID();
    com.example.starwars.modules.filmography.films.models.Film film =
        filmsRepository.findFilmById(filmId);
    if (film == null) {
      throw new IllegalArgumentException("Film with ID " + filmId + " not found");
    }
    return CompletableFuture.completedFuture(new FilmBuilder(context).build(film));
  }
}
