package com.example.starwars.modules.universe.planets.queries;

import com.example.starwars.modules.universe.planets.models.PlanetsFilmsRepository;
import com.example.starwars.universe.resolverbases.PlanetResolvers;
import io.micronaut.context.annotation.Prototype;
import jakarta.inject.Inject;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import viaduct.java.api.annotations.Resolver;
import viaduct.java.api.reflect.Type;
import viaduct.java.grts.Film;

/** Resolves films associated with a planet. */
@Resolver(objectValueFragment = "id")
@Prototype
public final class PlanetFilmsQueryResolver extends PlanetResolvers.Films {
  private final PlanetsFilmsRepository planetsFilmsRepository;

  @Inject
  public PlanetFilmsQueryResolver(PlanetsFilmsRepository planetsFilmsRepository) {
    this.planetsFilmsRepository = planetsFilmsRepository;
  }

  @Override
  public CompletableFuture<List<Film>> resolve(Context context) {
    String planetId = context.getObjectValue().getId().getInternalID();
    List<Film> films =
        planetsFilmsRepository.findFilmsByPlanetId(planetId).stream()
            .map(
                relation ->
                    context.nodeRef(
                        context.globalIDFor(Type.ofClass(Film.class), relation.filmId())))
            .toList();
    return CompletableFuture.completedFuture(films);
  }
}
