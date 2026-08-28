package com.example.starwars.modules.universe.planets.queries;

import com.example.starwars.modules.universe.planets.models.PlanetBuilder;
import com.example.starwars.modules.universe.planets.models.PlanetsRepository;
import com.example.starwars.universe.resolverbases.QueryResolvers;
import io.micronaut.context.annotation.Prototype;
import jakarta.inject.Inject;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import viaduct.java.api.annotations.Resolver;
import viaduct.java.grts.Planet;

/** Resolves the list of planets with an optional limit. */
@Resolver
@Prototype
public final class AllPlanetsQueryResolver extends QueryResolvers.AllPlanets {
  private static final int DEFAULT_PAGE_SIZE = 10;

  private final PlanetsRepository planetsRepository;

  @Inject
  public AllPlanetsQueryResolver(PlanetsRepository planetsRepository) {
    this.planetsRepository = planetsRepository;
  }

  @Override
  public CompletableFuture<List<Planet>> resolve(Context context) {
    Integer requestedLimit = context.getArguments().getLimit();
    int limit = requestedLimit == null ? DEFAULT_PAGE_SIZE : requestedLimit;
    List<Planet> planets =
        planetsRepository.findAll().stream()
            .limit(limit)
            .map(planet -> new PlanetBuilder(context).build(planet))
            .toList();
    return CompletableFuture.completedFuture(planets);
  }
}
