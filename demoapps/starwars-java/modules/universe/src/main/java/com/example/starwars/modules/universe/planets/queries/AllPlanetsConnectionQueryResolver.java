package com.example.starwars.modules.universe.planets.queries;

import com.example.starwars.modules.universe.planets.models.PlanetBuilder;
import com.example.starwars.modules.universe.planets.models.PlanetsRepository;
import com.example.starwars.universe.resolverbases.QueryResolvers;
import io.micronaut.context.annotation.Prototype;
import jakarta.inject.Inject;
import java.util.concurrent.CompletableFuture;
import viaduct.java.api.annotations.Resolver;
import viaduct.java.grts.PlanetsConnection;

/** Resolves backward pagination for planets using the generated connection builder. */
@Resolver
@Prototype
public final class AllPlanetsConnectionQueryResolver extends QueryResolvers.AllPlanetsConnection {
  private final PlanetsRepository planetsRepository;

  @Inject
  public AllPlanetsConnectionQueryResolver(PlanetsRepository planetsRepository) {
    this.planetsRepository = planetsRepository;
  }

  @Override
  public CompletableFuture<PlanetsConnection> resolve(Context context) {
    return CompletableFuture.completedFuture(
        PlanetsConnection.builder(context)
            .fromList(
                planetsRepository.findAll(), planet -> new PlanetBuilder(context).build(planet))
            .build());
  }
}
