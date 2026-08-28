package com.example.starwars.modules.universe.planets.models;

import java.time.Instant;
import java.util.List;

/** Entity class representing a planet in the Star Wars universe. */
public record Planet(
    String id,
    String name,
    Integer diameter,
    Integer rotationPeriod,
    Integer orbitalPeriod,
    Float gravity,
    Float population,
    Float surfaceWater,
    List<String> climates,
    List<String> terrains,
    Instant created,
    Instant edited) {
  public Planet(
      String id,
      String name,
      Integer diameter,
      Integer rotationPeriod,
      Integer orbitalPeriod,
      Float gravity,
      Float population,
      Float surfaceWater,
      List<String> climates,
      List<String> terrains) {
    this(
        id,
        name,
        diameter,
        rotationPeriod,
        orbitalPeriod,
        gravity,
        population,
        surfaceWater,
        climates,
        terrains,
        Instant.now(),
        Instant.now());
  }
}
