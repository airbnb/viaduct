package com.example.starwars.modules.filmography.films.resolvers;

import com.example.starwars.filmography.resolverbases.FilmResolvers;
import io.micronaut.context.annotation.Prototype;
import java.util.concurrent.CompletableFuture;
import viaduct.java.api.annotations.Resolver;

/** Computes a concise episode summary. */
@Resolver(objectValueFragment = "title episodeID director")
@Prototype
public final class FilmSummaryResolver extends FilmResolvers.Summary {
  @Override
  public CompletableFuture<String> resolve(Context context) {
    viaduct.java.grts.Film film = context.getObjectValue();
    return CompletableFuture.completedFuture(
        "Episode "
            + film.getEpisodeID()
            + ": "
            + film.getTitle()
            + " (Directed by "
            + film.getDirector()
            + ")");
  }
}
