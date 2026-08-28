package com.example.starwars.modules.universe.vehicles.models;

import viaduct.java.api.context.ExecutionContext;
import viaduct.java.api.reflect.Type;

/** Maps vehicle entities to generated GraphQL runtime types. */
public final class VehicleBuilder {
  private final ExecutionContext context;

  public VehicleBuilder(ExecutionContext context) {
    this.context = context;
  }

  public viaduct.java.grts.Vehicle build(Vehicle vehicle) {
    return viaduct.java.grts.Vehicle.builder(context)
        .id(context.globalIDFor(Type.ofClass(viaduct.java.grts.Vehicle.class), vehicle.id()))
        .name(vehicle.name())
        .model(vehicle.model())
        .vehicleClass(vehicle.vehicleClass())
        .manufacturers(vehicle.manufacturers())
        .costInCredits(
            vehicle.costInCredits() == null ? null : vehicle.costInCredits().doubleValue())
        .length(vehicle.length() == null ? null : vehicle.length().doubleValue())
        .crew(vehicle.crew())
        .passengers(vehicle.passengers())
        .maxAtmospheringSpeed(vehicle.maxAtmospheringSpeed())
        .cargoCapacity(
            vehicle.cargoCapacity() == null ? null : vehicle.cargoCapacity().doubleValue())
        .consumables(vehicle.consumables())
        .created(vehicle.created().toString())
        .edited(vehicle.edited().toString())
        .build();
  }
}
