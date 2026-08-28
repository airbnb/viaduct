package com.example.starwars.modules.universe.species.models;

import java.time.Instant;
import java.util.List;

/** Entity representing a species in the Star Wars universe. */
public record Species(
    String id,
    String name,
    String classification,
    String designation,
    Float averageHeight,
    Integer averageLifespan,
    List<String> eyeColors,
    List<String> hairColors,
    String language,
    String homeworldId,
    Instant created,
    Instant edited,
    SpeciesExtrasData extrasData) {
  public Species(
      String id,
      String name,
      String classification,
      String designation,
      Float averageHeight,
      Integer averageLifespan,
      List<String> eyeColors,
      List<String> hairColors,
      String language,
      String homeworldId,
      SpeciesExtrasData extrasData) {
    this(
        id,
        name,
        classification,
        designation,
        averageHeight,
        averageLifespan,
        eyeColors,
        hairColors,
        language,
        homeworldId,
        Instant.now(),
        Instant.now(),
        extrasData);
  }
}
