package com.example.starwars.modules.universe.planets.models;

import jakarta.inject.Singleton;
import java.util.List;

/** In-memory planet repository. */
@Singleton
public final class PlanetsRepository {
  public static final int UNKNOWN_DIAMETER = 0;

  private final List<Planet> planets =
      List.of(
          new Planet(
              "1", "Tatooine", 10465, 23, 304, 1f, 200000f, 1f, List.of("arid"), List.of("desert")),
          new Planet(
              "2",
              "Alderaan",
              12500,
              24,
              364,
              1f,
              2000000000f,
              40f,
              List.of("temperate"),
              List.of("grasslands", "mountains")),
          new Planet(
              "3",
              "Corellia",
              11000,
              25,
              329,
              1f,
              3000000000f,
              70f,
              List.of("temperate"),
              List.of("plains", "urban", "hills", "forests")),
          new Planet(
              "4",
              "Stewjon",
              UNKNOWN_DIAMETER,
              null,
              null,
              1f,
              null,
              null,
              List.of("temperate"),
              List.of("grass")),
          new Planet(
              "5",
              "Earth",
              UNKNOWN_DIAMETER,
              24,
              365,
              9.8f,
              8_000_000_000f,
              70f,
              List.of("temperate"),
              List.of("grass")),
          new Planet(
              "6",
              "Kashyyyk",
              12765,
              26,
              381,
              1f,
              45000000f,
              60f,
              List.of("tropical"),
              List.of("jungle", "forest", "lakes")));

  public List<Planet> findAll() {
    return planets;
  }

  public Planet findById(String id) {
    return planets.stream().filter(planet -> planet.id().equals(id)).findFirst().orElse(null);
  }
}
