package com.example.starwars.modules.universe.starships.queries;

import com.example.starwars.modules.universe.starships.models.Starship;
import com.example.starwars.modules.universe.starships.models.StarshipBuilder;
import com.example.starwars.modules.universe.starships.models.StarshipsRepository;
import com.example.starwars.universe.resolverbases.QueryResolvers;
import io.micronaut.context.annotation.Prototype;
import jakarta.inject.Inject;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import viaduct.java.api.annotations.Resolver;
import viaduct.java.api.types.OffsetLimit;
import viaduct.java.grts.StarshipsConnection;

/** Resolves forward pagination for starships using a limit-plus-one slice. */
@Resolver
@Prototype
public final class AllStarshipsConnectionQueryResolver
    extends QueryResolvers.AllStarshipsConnection {
  private final StarshipsRepository starshipsRepository;

  @Inject
  public AllStarshipsConnectionQueryResolver(StarshipsRepository starshipsRepository) {
    this.starshipsRepository = starshipsRepository;
  }

  @Override
  public CompletableFuture<StarshipsConnection> resolve(Context context) {
    OffsetLimit offsetLimit = context.getArguments().toOffsetLimit();
    List<Starship> allStarships = starshipsRepository.findAll();
    int fromIndex = Math.min(offsetLimit.offset(), allStarships.size());
    int toIndex = Math.min(allStarships.size(), fromIndex + offsetLimit.limit() + 1);
    List<Starship> slicePlusOne = allStarships.subList(fromIndex, toIndex);
    boolean hasNextPage = slicePlusOne.size() > offsetLimit.limit();

    return CompletableFuture.completedFuture(
        StarshipsConnection.builder(context)
            .fromSlice(
                slicePlusOne, hasNextPage, starship -> new StarshipBuilder(context).build(starship))
            .build());
  }
}
