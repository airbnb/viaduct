package com.example.starwars.modules.universe.starships.models;

import jakarta.inject.Singleton;
import java.util.List;

/** In-memory starship repository. */
@Singleton
public final class StarshipsRepository {
  private final List<Starship> starships =
      List.of(
          new Starship(
              "1",
              "Millennium Falcon",
              "YT-1300 light freighter",
              "Light freighter",
              List.of("Corellian Engineering Corporation"),
              100000f,
              34.37f,
              "4",
              "6",
              1050,
              0.5f,
              75,
              100000f,
              "2 months"),
          new Starship(
              "2",
              "X-wing",
              "T-65 X-wing",
              "Starfighter",
              List.of("Incom Corporation"),
              149999f,
              12.5f,
              "1",
              "0",
              1050,
              1.0f,
              100,
              110f,
              "1 week"));

  public List<Starship> findAll() {
    return starships;
  }

  public Starship findById(String id) {
    return starships.stream().filter(value -> value.id().equals(id)).findFirst().orElse(null);
  }
}
