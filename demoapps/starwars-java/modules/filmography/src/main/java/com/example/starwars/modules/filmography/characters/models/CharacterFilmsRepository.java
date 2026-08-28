package com.example.starwars.modules.filmography.characters.models;

import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** In-memory character-to-film relationship repository. */
@Singleton
public final class CharacterFilmsRepository {
  private final Map<String, List<String>> characterFilmRelations = new HashMap<>();

  public CharacterFilmsRepository() {
    for (String characterId : List.of("1", "2", "3", "4", "5")) {
      characterFilmRelations.put(characterId, new ArrayList<>(List.of("1", "2", "3")));
    }
  }

  public List<String> findFilmsByCharacterId(String characterId) {
    return characterFilmRelations.getOrDefault(characterId, List.of());
  }

  public void addCharacterToFilm(String characterId, String filmId) {
    List<String> films =
        characterFilmRelations.computeIfAbsent(characterId, ignored -> new ArrayList<>());
    if (films.contains(filmId)) {
      throw new IllegalArgumentException(
          "Character with ID " + characterId + " is already in film with ID " + filmId);
    }
    films.add(filmId);
  }

  public void removeCharacter(String internalId) {
    characterFilmRelations.remove(internalId);
  }
}
