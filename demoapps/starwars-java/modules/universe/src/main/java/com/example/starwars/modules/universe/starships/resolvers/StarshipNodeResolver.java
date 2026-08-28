package com.example.starwars.modules.universe.starships.resolvers;

import com.example.starwars.modules.universe.starships.models.Starship;
import com.example.starwars.modules.universe.starships.models.StarshipBuilder;
import com.example.starwars.modules.universe.starships.models.StarshipsRepository;
import com.example.starwars.universe.resolverbases.NodeResolvers;
import io.micronaut.context.annotation.Prototype;
import jakarta.inject.Inject;
import java.util.concurrent.CompletableFuture;
import viaduct.java.api.annotations.Resolver;

/** Node resolver for starships. */
@Resolver
@Prototype
public final class StarshipNodeResolver extends NodeResolvers.Starship {
  private final StarshipsRepository starshipsRepository;

  @Inject
  public StarshipNodeResolver(StarshipsRepository starshipsRepository) {
    this.starshipsRepository = starshipsRepository;
  }

  @Override
  public CompletableFuture<viaduct.java.grts.Starship> resolve(Context context) {
    String id = context.getId().getInternalID();
    Starship starship = starshipsRepository.findById(id);
    if (starship == null) {
      return CompletableFuture.failedFuture(
          new IllegalArgumentException("Starship with ID " + id + " not found"));
    }
    return CompletableFuture.completedFuture(new StarshipBuilder(context).build(starship));
  }
}
