package com.example.starwars.modules.universe.species.queries;

import com.example.starwars.modules.universe.species.models.Species;
import com.example.starwars.modules.universe.species.models.SpeciesBuilder;
import com.example.starwars.modules.universe.species.models.SpeciesRepository;
import com.example.starwars.universe.resolverbases.QueryResolvers;
import io.micronaut.context.annotation.Prototype;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import viaduct.java.api.annotations.Resolver;
import viaduct.java.api.types.ConnectionArguments;
import viaduct.java.api.types.OffsetCursor;
import viaduct.java.api.types.OffsetLimit;
import viaduct.java.grts.SpeciesConnection;
import viaduct.java.grts.SpeciesEdge;

/** Resolves multidirectional species pagination using manually constructed edges. */
@Resolver
@Prototype
public final class AllSpeciesConnectionQueryResolver extends QueryResolvers.AllSpeciesConnection {
  private final SpeciesRepository speciesRepository;

  @Inject
  public AllSpeciesConnectionQueryResolver(SpeciesRepository speciesRepository) {
    this.speciesRepository = speciesRepository;
  }

  @Override
  public CompletableFuture<SpeciesConnection> resolve(Context context) {
    OffsetLimit offsetLimit =
        context.getArguments().requiresTotalCountForOffsetLimit()
            ? context
                .getArguments()
                .toOffsetLimit(speciesRepository.count(), ConnectionArguments.DEFAULT_PAGE_SIZE)
            : context.getArguments().toOffsetLimit();

    List<Species> slicePlusOne =
        speciesRepository.findSome(offsetLimit.limit() + 1, offsetLimit.offset());
    boolean hasNextPage = slicePlusOne.size() > offsetLimit.limit();
    boolean hasPreviousPage = offsetLimit.offset() > 0;
    int edgeCount = Math.min(offsetLimit.limit(), slicePlusOne.size());
    List<SpeciesEdge> edges = new ArrayList<>(edgeCount);
    for (int index = 0; index < edgeCount; index++) {
      edges.add(
          SpeciesEdge.builder(context)
              .node(new SpeciesBuilder(context).build(slicePlusOne.get(index)))
              .cursor(OffsetCursor.fromOffset(offsetLimit.offset() + index).getValue())
              .build());
    }

    return CompletableFuture.completedFuture(
        SpeciesConnection.builder(context).fromEdges(edges, hasNextPage, hasPreviousPage).build());
  }
}
