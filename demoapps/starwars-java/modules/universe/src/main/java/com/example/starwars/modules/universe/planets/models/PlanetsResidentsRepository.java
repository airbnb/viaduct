package com.example.starwars.modules.universe.planets.models;

import jakarta.inject.Singleton;
import java.util.List;
import java.util.Map;

/** In-memory relationships between planets and their residents. */
@Singleton
public final class PlanetsResidentsRepository {
  private final Map<String, List<String>> planetResidents =
      Map.of(
          "1", List.of("1", "4"),
          "2", List.of("2"),
          "3", List.of("3"),
          "4", List.of("5"));

  public Map<String, List<String>> getPlanetResidents() {
    return planetResidents;
  }

  public List<PlanetCharacter> findResidentsByPlanetId(String planetId) {
    return planetResidents.getOrDefault(planetId, List.of()).stream()
        .map(characterId -> new PlanetCharacter(characterId, planetId))
        .toList();
  }
}
