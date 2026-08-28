package com.example.starwars.modules.filmography.films.models;

import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** In-memory film-to-character relationship repository. */
@Singleton
public final class FilmCharactersRepository {
  private final Map<String, List<String>> filmCharacterRelations = new HashMap<>();

  public FilmCharactersRepository() {
    for (String filmId : List.of("1", "2", "3")) {
      filmCharacterRelations.put(filmId, new ArrayList<>(List.of("1", "2", "3", "4", "5")));
    }
  }

  public List<String> findCharactersByFilmId(String filmId) {
    return filmCharacterRelations.getOrDefault(filmId, List.of());
  }

  public void addCharacterToFilm(String filmId, String characterId) {
    List<String> characters =
        filmCharacterRelations.computeIfAbsent(filmId, ignored -> new ArrayList<>());
    if (characters.contains(characterId)) {
      throw new IllegalArgumentException(
          "Film with ID " + filmId + " already has character with ID " + characterId);
    }
    characters.add(characterId);
  }

  public void removeCharacter(String internalId) {
    filmCharacterRelations.values().forEach(characters -> characters.remove(internalId));
  }
}
