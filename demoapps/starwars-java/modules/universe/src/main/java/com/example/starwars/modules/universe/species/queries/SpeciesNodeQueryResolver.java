package com.example.starwars.modules.universe.species.queries;

import com.example.starwars.modules.universe.species.models.SpeciesBuilder;
import com.example.starwars.modules.universe.species.models.SpeciesRepository;
import com.example.starwars.universe.resolverbases.NodeResolvers;
import io.micronaut.context.annotation.Prototype;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import viaduct.java.api.annotations.Resolver;
import viaduct.java.api.resolvers.FieldValue;
import viaduct.java.grts.Species;

/** Batched node resolver for species. */
@Resolver
@Prototype
public final class SpeciesNodeQueryResolver extends NodeResolvers.Species {
  private final SpeciesRepository speciesRepository;

  @Inject
  public SpeciesNodeQueryResolver(SpeciesRepository speciesRepository) {
    this.speciesRepository = speciesRepository;
  }

  @Override
  public CompletableFuture<Map<Context, FieldValue<Species>>> batchResolve(List<Context> contexts) {
    Map<Context, FieldValue<Species>> results = new LinkedHashMap<>();
    for (Context context : contexts) {
      String speciesId = context.getId().getInternalID();
      com.example.starwars.modules.universe.species.models.Species species =
          speciesRepository.findById(speciesId);
      FieldValue<Species> value =
          species == null
              ? FieldValue.ofError(new IllegalArgumentException("Specie not found: " + speciesId))
              : FieldValue.ofValue(new SpeciesBuilder(context).build(species));
      results.put(context, value);
    }
    return CompletableFuture.completedFuture(results);
  }
}
