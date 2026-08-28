package com.example.starwars.modules.filmography.films.queries;

import com.example.starwars.filmography.resolverbases.QueryResolvers;
import com.example.starwars.modules.filmography.films.models.FilmBuilder;
import com.example.starwars.modules.filmography.films.models.FilmsRepository;
import io.micronaut.context.annotation.Prototype;
import jakarta.inject.Inject;
import java.util.concurrent.CompletableFuture;
import viaduct.java.api.annotations.Resolver;
import viaduct.java.grts.FilmsConnection;

/** Resolves Relay-style film pagination. */
@Resolver
@Prototype
public final class AllFilmsConnectionQueryResolver extends QueryResolvers.AllFilmsConnection {
  private final FilmsRepository filmsRepository;

  @Inject
  public AllFilmsConnectionQueryResolver(FilmsRepository filmsRepository) {
    this.filmsRepository = filmsRepository;
  }

  @Override
  public CompletableFuture<FilmsConnection> resolve(Context context) {
    return CompletableFuture.completedFuture(
        FilmsConnection.builder(context)
            .totalCount(filmsRepository.getAllFilms().size())
            .fromList(filmsRepository.getAllFilms(), film -> new FilmBuilder(context).build(film))
            .build());
  }
}
