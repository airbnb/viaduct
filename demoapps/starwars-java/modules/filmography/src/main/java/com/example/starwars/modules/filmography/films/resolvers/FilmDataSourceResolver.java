package com.example.starwars.modules.filmography.films.resolvers;

import com.example.starwars.common.ExternalDataClient;
import com.example.starwars.filmography.resolverbases.FilmResolvers;
import io.micronaut.context.annotation.Prototype;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.util.concurrent.CompletableFuture;
import viaduct.java.api.annotations.Resolver;

/** Demonstrates named per-module dependency injection. */
@Resolver(objectValueFragment = "id")
@Prototype
public final class FilmDataSourceResolver extends FilmResolvers.DataSource {
  private final ExternalDataClient externalDataClient;

  @Inject
  public FilmDataSourceResolver(@Named("filmography") ExternalDataClient externalDataClient) {
    this.externalDataClient = externalDataClient;
  }

  @Override
  public CompletableFuture<String> resolve(Context context) {
    return CompletableFuture.completedFuture(
        externalDataClient.fetchData(context.getObjectValue().getId().getInternalID()));
  }
}
