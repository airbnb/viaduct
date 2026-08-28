package com.example.starwars.modules.universe.vehicles.resolvers;

import com.example.starwars.modules.universe.vehicles.models.Vehicle;
import com.example.starwars.modules.universe.vehicles.models.VehicleBuilder;
import com.example.starwars.modules.universe.vehicles.models.VehiclesRepository;
import com.example.starwars.universe.resolverbases.NodeResolvers;
import io.micronaut.context.annotation.Prototype;
import jakarta.inject.Inject;
import java.util.concurrent.CompletableFuture;
import viaduct.java.api.annotations.Resolver;

/** Node resolver for vehicles. */
@Resolver
@Prototype
public final class VehicleNodeResolver extends NodeResolvers.Vehicle {
  private final VehiclesRepository vehiclesRepository;

  @Inject
  public VehicleNodeResolver(VehiclesRepository vehiclesRepository) {
    this.vehiclesRepository = vehiclesRepository;
  }

  @Override
  public CompletableFuture<viaduct.java.grts.Vehicle> resolve(Context context) {
    String id = context.getId().getInternalID();
    Vehicle vehicle = vehiclesRepository.findById(id);
    if (vehicle == null) {
      return CompletableFuture.failedFuture(
          new IllegalArgumentException("Vehicle with ID " + id + " not found"));
    }
    return CompletableFuture.completedFuture(new VehicleBuilder(context).build(vehicle));
  }
}
