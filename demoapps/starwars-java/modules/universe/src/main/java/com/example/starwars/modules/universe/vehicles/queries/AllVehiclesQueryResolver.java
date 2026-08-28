package com.example.starwars.modules.universe.vehicles.queries;

import com.example.starwars.modules.universe.vehicles.models.VehicleBuilder;
import com.example.starwars.modules.universe.vehicles.models.VehiclesRepository;
import com.example.starwars.universe.resolverbases.QueryResolvers;
import io.micronaut.context.annotation.Prototype;
import jakarta.inject.Inject;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import viaduct.java.api.annotations.Resolver;
import viaduct.java.grts.Vehicle;

/** Resolves the list of vehicles with an optional limit. */
@Resolver
@Prototype
public final class AllVehiclesQueryResolver extends QueryResolvers.AllVehicles {
  private static final int DEFAULT_PAGE_SIZE = 10;

  private final VehiclesRepository vehiclesRepository;

  @Inject
  public AllVehiclesQueryResolver(VehiclesRepository vehiclesRepository) {
    this.vehiclesRepository = vehiclesRepository;
  }

  @Override
  public CompletableFuture<List<Vehicle>> resolve(Context context) {
    Integer requestedLimit = context.getArguments().getLimit();
    int limit = requestedLimit == null ? DEFAULT_PAGE_SIZE : requestedLimit;
    List<Vehicle> vehicles =
        vehiclesRepository.findAll().stream()
            .limit(limit)
            .map(vehicle -> new VehicleBuilder(context).build(vehicle))
            .toList();
    return CompletableFuture.completedFuture(vehicles);
  }
}
