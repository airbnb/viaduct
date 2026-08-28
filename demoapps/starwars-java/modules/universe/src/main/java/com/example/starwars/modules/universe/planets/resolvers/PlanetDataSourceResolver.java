package com.example.starwars.modules.universe.planets.resolvers;

import com.example.starwars.common.ExternalDataClient;
import com.example.starwars.universe.resolverbases.PlanetResolvers;
import io.micronaut.context.annotation.Prototype;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.util.concurrent.CompletableFuture;
import viaduct.java.api.annotations.Resolver;

/** Demonstrates qualified per-tenant dependency injection. */
@Resolver(objectValueFragment = "id")
@Prototype
public final class PlanetDataSourceResolver extends PlanetResolvers.DataSource {
  private final ExternalDataClient externalDataClient;

  @Inject
  public PlanetDataSourceResolver(@Named("universe") ExternalDataClient externalDataClient) {
    this.externalDataClient = externalDataClient;
  }

  @Override
  public CompletableFuture<String> resolve(Context context) {
    String planetId = context.getObjectValue().getId().getInternalID();
    return CompletableFuture.completedFuture(externalDataClient.fetchData(planetId));
  }
}
