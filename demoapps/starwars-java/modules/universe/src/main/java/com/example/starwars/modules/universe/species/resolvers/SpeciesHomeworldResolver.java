package com.example.starwars.modules.universe.species.resolvers;

import com.example.starwars.modules.universe.species.models.Species;
import com.example.starwars.modules.universe.species.models.SpeciesRepository;
import com.example.starwars.universe.resolverbases.SpeciesResolvers;
import io.micronaut.context.annotation.Prototype;
import jakarta.inject.Inject;
import java.util.concurrent.CompletableFuture;
import viaduct.java.api.annotations.Resolver;
import viaduct.java.api.reflect.Type;
import viaduct.java.grts.Planet;

/** Resolves the homeworld for a species. */
@Resolver(objectValueFragment = "id")
@Prototype
public final class SpeciesHomeworldResolver extends SpeciesResolvers.Homeworld {
  private final SpeciesRepository speciesRepository;

  @Inject
  public SpeciesHomeworldResolver(SpeciesRepository speciesRepository) {
    this.speciesRepository = speciesRepository;
  }

  @Override
  public CompletableFuture<Planet> resolve(Context context) {
    Species species = speciesRepository.findById(context.getObjectValue().getId().getInternalID());
    if (species == null || species.homeworldId() == null) {
      return CompletableFuture.completedFuture(null);
    }
    Planet homeworld =
        context.nodeRef(context.globalIDFor(Type.ofClass(Planet.class), species.homeworldId()));
    return CompletableFuture.completedFuture(homeworld);
  }
}
