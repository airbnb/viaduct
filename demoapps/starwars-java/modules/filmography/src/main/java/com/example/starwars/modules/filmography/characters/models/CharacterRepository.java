package com.example.starwars.modules.filmography.characters.models;

import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** In-memory character repository used by the demo resolvers. */
@Singleton
public final class CharacterRepository {
  private final AtomicInteger characterIdSequence = new AtomicInteger(1);
  private final List<Character> characters = new ArrayList<>();

  public CharacterRepository() {
    characters.add(
        character("Luke Skywalker", "19BBY", "blue", "male", "blond", 172, 77f, "1", "1"));
    characters.add(
        character("Princess Leia", "19BBY", "brown", "female", "brown", 150, 49f, "2", "1"));
    characters.add(character("Han Solo", "29BBY", "brown", "male", "brown", 180, 80f, "3", "1"));
    characters.add(
        character("Darth Vader", "41.9BBY", "yellow", "male", "none", 202, 136f, "1", "1"));
    characters.add(
        character(
            "Obi-Wan Kenobi", "57BBY", "blue-gray", "male", "auburn, white", 182, 77f, "4", "1"));
  }

  public List<Character> findAll() {
    return List.copyOf(characters);
  }

  public Map<String, Character> findCharactersAsMap(List<String> characterIds) {
    Map<String, Character> result = new LinkedHashMap<>();
    characterIds.forEach(
        id -> {
          Character character = findById(id);
          if (character != null) {
            result.put(id, character);
          }
        });
    return result;
  }

  public Character findById(String id) {
    return characters.stream()
        .filter(character -> character.id().equals(id))
        .findFirst()
        .orElse(null);
  }

  public List<Character> findCharactersByName(String name) {
    String normalizedName = name.toLowerCase(Locale.ROOT);
    return characters.stream()
        .filter(character -> character.name().toLowerCase(Locale.ROOT).contains(normalizedName))
        .toList();
  }

  public List<Character> findCharactersByIdList(List<String> ids) {
    return characters.stream().filter(character -> ids.contains(character.id())).toList();
  }

  public List<Character> findCharactersByYearOfBirth(String birthYear) {
    return characters.stream()
        .filter(character -> birthYear.equals(character.birthYear()))
        .toList();
  }

  public Character add(Character character) {
    Character newCharacter =
        character.withId(Integer.toString(characterIdSequence.getAndIncrement()));
    characters.add(newCharacter);
    return newCharacter;
  }

  public boolean delete(String id) {
    return characters.removeIf(character -> character.id().equals(id));
  }

  public Character update(Character updatedCharacter) {
    for (int index = 0; index < characters.size(); index++) {
      if (characters.get(index).id().equals(updatedCharacter.id())) {
        characters.set(index, updatedCharacter);
        return updatedCharacter;
      }
    }
    throw new IllegalArgumentException("Character with ID " + updatedCharacter.id() + " not found");
  }

  private Character character(
      String name,
      String birthYear,
      String eyeColor,
      String gender,
      String hairColor,
      Integer height,
      Float mass,
      String homeworldId,
      String speciesId) {
    return new Character(
        Integer.toString(characterIdSequence.getAndIncrement()),
        name,
        birthYear,
        eyeColor,
        gender,
        hairColor,
        height,
        mass,
        homeworldId,
        speciesId);
  }
}
