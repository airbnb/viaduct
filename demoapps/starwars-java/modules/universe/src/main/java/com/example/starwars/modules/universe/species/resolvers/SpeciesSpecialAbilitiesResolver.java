package com.example.starwars.modules.universe.species.resolvers;

import com.example.starwars.modules.universe.species.models.Species;
import com.example.starwars.modules.universe.species.models.SpeciesRepository;
import com.example.starwars.universe.resolverbases.SpeciesResolvers;
import io.micronaut.context.annotation.Prototype;
import jakarta.inject.Inject;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import viaduct.java.api.annotations.Resolver;

/** Resolves special abilities from species extras data. */
@Resolver(objectValueFragment = "id")
@Prototype
public final class SpeciesSpecialAbilitiesResolver extends SpeciesResolvers.SpecialAbilities {
  private final SpeciesRepository speciesRepository;

  @Inject
  public SpeciesSpecialAbilitiesResolver(SpeciesRepository speciesRepository) {
    this.speciesRepository = speciesRepository;
  }

  @Override
  public CompletableFuture<List<String>> resolve(Context context) {
    Species species = speciesRepository.findById(context.getObjectValue().getId().getInternalID());
    return CompletableFuture.completedFuture(
        species == null ? null : species.extrasData().specialAbilities());
  }
}
