package com.example.starwars.modules.universe.vehicles.models;

import jakarta.inject.Singleton;
import java.util.List;

/** In-memory vehicle repository. */
@Singleton
public final class VehiclesRepository {
  private final List<Vehicle> vehicles =
      List.of(
          new Vehicle(
              "1",
              "Speeder bike",
              "74-Z speeder bike",
              "speeder",
              List.of("Aratech Repulsor Company"),
              8000f,
              3f,
              "1",
              "1",
              360,
              4f,
              "1 day"));

  public List<Vehicle> findAll() {
    return vehicles;
  }

  public Vehicle findById(String id) {
    return vehicles.stream().filter(value -> value.id().equals(id)).findFirst().orElse(null);
  }
}
