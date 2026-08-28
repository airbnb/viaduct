package com.example.starwars.modules.filmography.films.resolvers;

import com.example.starwars.filmography.resolverbases.FilmResolvers;
import io.micronaut.context.annotation.Prototype;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import viaduct.java.api.annotations.Resolver;

/** Computes release and production details from selected film fields. */
@Resolver(objectValueFragment = "fragment _ on Film { title director producers releaseDate }")
@Prototype
public final class FilmProductionDetailsResolver extends FilmResolvers.ProductionDetails {
  @Override
  public CompletableFuture<String> resolve(Context context) {
    viaduct.java.grts.Film film = context.getObjectValue();
    String producers =
        film.getProducers() == null
            ? "Unknown producers"
            : film.getProducers().stream()
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.joining(", "));
    return CompletableFuture.completedFuture(
        film.getTitle()
            + " was released on "
            + film.getReleaseDate()
            + ", directed by "
            + film.getDirector()
            + " and produced by "
            + producers);
  }
}
