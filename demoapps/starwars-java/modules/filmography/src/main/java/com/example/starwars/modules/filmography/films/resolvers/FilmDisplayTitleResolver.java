package com.example.starwars.modules.filmography.films.resolvers;

import com.example.starwars.filmography.resolverbases.FilmResolvers;
import io.micronaut.context.annotation.Prototype;
import java.util.concurrent.CompletableFuture;
import viaduct.java.api.annotations.Resolver;

/** Delegates display title resolution to the title field. */
@Resolver(objectValueFragment = "title")
@Prototype
public final class FilmDisplayTitleResolver extends FilmResolvers.DisplayTitle {
  @Override
  public CompletableFuture<String> resolve(Context context) {
    return CompletableFuture.completedFuture(context.getObjectValue().getTitle());
  }
}
