package com.example.starwars.modules.universe.planets.resolvers;

import com.example.starwars.modules.universe.planets.models.PlanetBuilder;
import com.example.starwars.modules.universe.planets.models.PlanetsRepository;
import com.example.starwars.universe.resolverbases.NodeResolvers;
import io.micronaut.context.annotation.Prototype;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import viaduct.java.api.annotations.Resolver;
import viaduct.java.api.resolvers.FieldValue;
import viaduct.java.grts.Planet;

/** Batched node resolver for planets. */
@Resolver
@Prototype
public final class PlanetNodeResolver extends NodeResolvers.Planet {
  private final PlanetsRepository planetsRepository;

  @Inject
  public PlanetNodeResolver(PlanetsRepository planetsRepository) {
    this.planetsRepository = planetsRepository;
  }

  @Override
  public CompletableFuture<Map<Context, FieldValue<Planet>>> batchResolve(List<Context> contexts) {
    Map<Context, FieldValue<Planet>> results = new LinkedHashMap<>();
    for (Context context : contexts) {
      String planetId = context.getId().getInternalID();
      com.example.starwars.modules.universe.planets.models.Planet planet =
          planetsRepository.findById(planetId);
      FieldValue<Planet> value =
          planet == null
              ? FieldValue.ofError(new IllegalArgumentException("Planet not found: " + planetId))
              : FieldValue.ofValue(new PlanetBuilder(context).build(planet));
      results.put(context, value);
    }
    return CompletableFuture.completedFuture(results);
  }
}
