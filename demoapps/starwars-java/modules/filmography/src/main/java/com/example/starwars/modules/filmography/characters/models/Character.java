package com.example.starwars.modules.filmography.characters.models;

import java.time.Instant;

/** Character entity stored by the demo repository. */
public record Character(
    String id,
    String name,
    String birthYear,
    String eyeColor,
    String gender,
    String hairColor,
    Integer height,
    Float mass,
    String homeworldId,
    String speciesId,
    Instant created,
    Instant edited) {

  public Character(
      String id,
      String name,
      String birthYear,
      String eyeColor,
      String gender,
      String hairColor,
      Integer height,
      Float mass,
      String homeworldId,
      String speciesId) {
    this(
        id,
        name,
        birthYear,
        eyeColor,
        gender,
        hairColor,
        height,
        mass,
        homeworldId,
        speciesId,
        Instant.now(),
        Instant.now());
  }

  public Character withId(String newId) {
    return new Character(
        newId,
        name,
        birthYear,
        eyeColor,
        gender,
        hairColor,
        height,
        mass,
        homeworldId,
        speciesId,
        created,
        edited);
  }

  public Character withName(String newName) {
    return new Character(
        id,
        newName,
        birthYear,
        eyeColor,
        gender,
        hairColor,
        height,
        mass,
        homeworldId,
        speciesId,
        created,
        edited);
  }
}
