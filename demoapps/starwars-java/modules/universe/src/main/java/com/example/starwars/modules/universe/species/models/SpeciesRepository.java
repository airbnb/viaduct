package com.example.starwars.modules.universe.species.models;

import jakarta.inject.Singleton;
import java.util.List;

/** In-memory species repository. */
@Singleton
public final class SpeciesRepository {
  private final List<Species> species =
      List.of(
          new Species(
              "1",
              "Human",
              "mammal",
              "sentient",
              180f,
              120,
              List.of("brown", "blue", "green", "hazel", "grey", "amber"),
              List.of("blonde", "brown", "black", "red"),
              "Galactic Basic",
              "5",
              new SpeciesExtrasData(
                  "Diverse species with strong adaptability and technological advancement",
                  "Common",
                  List.of("Force sensitivity (rare)", "Adaptability", "Innovation"),
                  "Advanced")),
          new Species(
              "2",
              "Wookiee",
              "mammal",
              "sentient",
              210f,
              400,
              List.of("blue", "brown", "green"),
              List.of("brown", "black"),
              "Shyriiwook",
              "5",
              new SpeciesExtrasData()));

  public List<Species> findAll() {
    return species;
  }

  public List<Species> findSome(int limit, int offset) {
    if (offset >= species.size() || limit <= 0) {
      return List.of();
    }
    return species.subList(offset, Math.min(species.size(), offset + limit));
  }

  public int count() {
    return species.size();
  }

  public Species findById(String id) {
    return species.stream().filter(value -> value.id().equals(id)).findFirst().orElse(null);
  }

  public List<Species> findByHomeworldId(String homeworldId) {
    return species.stream().filter(value -> homeworldId.equals(value.homeworldId())).toList();
  }
}
