package com.example.starwars.modules.filmography.films.models;

import java.util.List;

/** Backing data shared by film cast resolvers. */
public record FilmCastData(List<String> characterIds) {
  public FilmCastData {
    characterIds = List.copyOf(characterIds);
  }
}
