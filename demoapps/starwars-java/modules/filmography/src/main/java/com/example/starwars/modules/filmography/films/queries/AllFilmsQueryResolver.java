package com.example.starwars.modules.filmography.films.queries;

import com.example.starwars.filmography.resolverbases.QueryResolvers;
import com.example.starwars.modules.filmography.films.models.FilmBuilder;
import com.example.starwars.modules.filmography.films.models.FilmsRepository;
import io.micronaut.context.annotation.Prototype;
import jakarta.inject.Inject;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import viaduct.java.api.annotations.Resolver;
import viaduct.java.grts.Film;

/** Resolves films with optional limit-based pagination. */
@Resolver
@Prototype
public final class AllFilmsQueryResolver extends QueryResolvers.AllFilms {
  private static final int DEFAULT_PAGE_SIZE = 10;

  private final FilmsRepository filmsRepository;

  @Inject
  public AllFilmsQueryResolver(FilmsRepository filmsRepository) {
    this.filmsRepository = filmsRepository;
  }

  @Override
  public CompletableFuture<List<Film>> resolve(Context context) {
    Integer requestedLimit = context.getArguments().getLimit();
    int limit = requestedLimit == null ? DEFAULT_PAGE_SIZE : requestedLimit;
    List<Film> films =
        filmsRepository.getAllFilms().stream()
            .limit(limit)
            .map(film -> new FilmBuilder(context).build(film))
            .toList();
    return CompletableFuture.completedFuture(films);
  }
}
