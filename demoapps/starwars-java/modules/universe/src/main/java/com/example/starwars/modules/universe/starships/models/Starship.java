package com.example.starwars.modules.universe.starships.models;

import java.time.Instant;
import java.util.List;

/** Entity representing a starship. */
public record Starship(
    String id,
    String name,
    String model,
    String starshipClass,
    List<String> manufacturers,
    Float costInCredits,
    Float length,
    String crew,
    String passengers,
    Integer maxAtmospheringSpeed,
    Float hyperdriveRating,
    Integer mglt,
    Float cargoCapacity,
    String consumables,
    Instant created,
    Instant edited) {
  public Starship(
      String id,
      String name,
      String model,
      String starshipClass,
      List<String> manufacturers,
      Float costInCredits,
      Float length,
      String crew,
      String passengers,
      Integer maxAtmospheringSpeed,
      Float hyperdriveRating,
      Integer mglt,
      Float cargoCapacity,
      String consumables) {
    this(
        id,
        name,
        model,
        starshipClass,
        manufacturers,
        costInCredits,
        length,
        crew,
        passengers,
        maxAtmospheringSpeed,
        hyperdriveRating,
        mglt,
        cargoCapacity,
        consumables,
        Instant.now(),
        Instant.now());
  }
}
