package com.example.starwars.modules.universe.planets.models;

import jakarta.inject.Singleton;
import java.util.List;
import java.util.Map;

/** In-memory relationships between planets and films. */
@Singleton
public final class PlanetsFilmsRepository {
  private final Map<String, List<String>> planetFilmRelations =
      Map.of(
          "1", List.of("1", "2", "3"),
          "2", List.of("1", "2", "3"),
          "3", List.of("1", "2", "3"),
          "4", List.of("1", "2", "3"),
          "5", List.of("1", "2", "3"));

  public List<PlanetFilms> findFilmsByPlanetId(String planetId) {
    return planetFilmRelations.getOrDefault(planetId, List.of()).stream()
        .map(filmId -> new PlanetFilms(filmId, planetId))
        .toList();
  }
}
