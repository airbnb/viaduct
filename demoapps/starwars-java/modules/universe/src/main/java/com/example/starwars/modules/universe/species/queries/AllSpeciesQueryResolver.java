package com.example.starwars.modules.universe.species.queries;

import com.example.starwars.modules.universe.species.models.SpeciesBuilder;
import com.example.starwars.modules.universe.species.models.SpeciesRepository;
import com.example.starwars.universe.resolverbases.QueryResolvers;
import io.micronaut.context.annotation.Prototype;
import jakarta.inject.Inject;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import viaduct.java.api.annotations.Resolver;
import viaduct.java.grts.Species;

/** Resolves the list of species with an optional limit. */
@Resolver
@Prototype
public final class AllSpeciesQueryResolver extends QueryResolvers.AllSpecies {
  private static final int DEFAULT_PAGE_SIZE = 10;

  private final SpeciesRepository speciesRepository;

  @Inject
  public AllSpeciesQueryResolver(SpeciesRepository speciesRepository) {
    this.speciesRepository = speciesRepository;
  }

  @Override
  public CompletableFuture<List<Species>> resolve(Context context) {
    Integer requestedLimit = context.getArguments().getLimit();
    int limit = requestedLimit == null ? DEFAULT_PAGE_SIZE : requestedLimit;
    List<Species> species =
        speciesRepository.findAll().stream()
            .limit(limit)
            .map(value -> new SpeciesBuilder(context).build(value))
            .toList();
    return CompletableFuture.completedFuture(species);
  }
}
