package com.example.starwars.modules.universe.planets.queries;

import com.example.starwars.modules.universe.planets.models.PlanetsResidentsRepository;
import com.example.starwars.universe.resolverbases.PlanetResolvers;
import io.micronaut.context.annotation.Prototype;
import jakarta.inject.Inject;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import viaduct.java.api.annotations.Resolver;
import viaduct.java.api.reflect.Type;
import viaduct.java.grts.Character;

/** Resolves characters who live on a planet. */
@Resolver(objectValueFragment = "id")
@Prototype
public final class PlanetResidentsQueryResolver extends PlanetResolvers.Residents {
  private final PlanetsResidentsRepository planetsResidentsRepository;

  @Inject
  public PlanetResidentsQueryResolver(PlanetsResidentsRepository planetsResidentsRepository) {
    this.planetsResidentsRepository = planetsResidentsRepository;
  }

  @Override
  public CompletableFuture<List<Character>> resolve(Context context) {
    String planetId = context.getObjectValue().getId().getInternalID();
    List<Character> residents =
        planetsResidentsRepository.findResidentsByPlanetId(planetId).stream()
            .map(
                relation ->
                    context.nodeRef(
                        context.globalIDFor(Type.ofClass(Character.class), relation.characterId())))
            .toList();
    return CompletableFuture.completedFuture(residents);
  }
}
