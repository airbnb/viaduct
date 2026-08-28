package com.example.starwars.modules.universe.starships.queries;

import com.example.starwars.modules.universe.starships.models.StarshipBuilder;
import com.example.starwars.modules.universe.starships.models.StarshipsRepository;
import com.example.starwars.universe.resolverbases.QueryResolvers;
import io.micronaut.context.annotation.Prototype;
import jakarta.inject.Inject;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import viaduct.java.api.annotations.Resolver;
import viaduct.java.grts.Starship;

/** Resolves the list of starships with an optional limit. */
@Resolver
@Prototype
public final class AllStarshipsQueryResolver extends QueryResolvers.AllStarships {
  private static final int DEFAULT_PAGE_SIZE = 10;

  private final StarshipsRepository starshipsRepository;

  @Inject
  public AllStarshipsQueryResolver(StarshipsRepository starshipsRepository) {
    this.starshipsRepository = starshipsRepository;
  }

  @Override
  public CompletableFuture<List<Starship>> resolve(Context context) {
    Integer requestedLimit = context.getArguments().getLimit();
    int limit = requestedLimit == null ? DEFAULT_PAGE_SIZE : requestedLimit;
    List<Starship> starships =
        starshipsRepository.findAll().stream()
            .limit(limit)
            .map(starship -> new StarshipBuilder(context).build(starship))
            .toList();
    return CompletableFuture.completedFuture(starships);
  }
}
