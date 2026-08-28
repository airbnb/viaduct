package com.example.starwars.modules.universe.vehicles.models;

import java.time.Instant;
import java.util.List;

/** Entity representing a vehicle. */
public record Vehicle(
    String id,
    String name,
    String model,
    String vehicleClass,
    List<String> manufacturers,
    Float costInCredits,
    Float length,
    String crew,
    String passengers,
    Integer maxAtmospheringSpeed,
    Float cargoCapacity,
    String consumables,
    Instant created,
    Instant edited) {
  public Vehicle(
      String id,
      String name,
      String model,
      String vehicleClass,
      List<String> manufacturers,
      Float costInCredits,
      Float length,
      String crew,
      String passengers,
      Integer maxAtmospheringSpeed,
      Float cargoCapacity,
      String consumables) {
    this(
        id,
        name,
        model,
        vehicleClass,
        manufacturers,
        costInCredits,
        length,
        crew,
        passengers,
        maxAtmospheringSpeed,
        cargoCapacity,
        consumables,
        Instant.now(),
        Instant.now());
  }
}
