package com.example.starwars.modules.filmography.films.resolvers;

import com.example.starwars.filmography.resolverbases.FilmResolvers;
import com.example.starwars.modules.filmography.characters.models.CharacterBuilder;
import com.example.starwars.modules.filmography.characters.models.CharacterRepository;
import com.example.starwars.modules.filmography.films.models.FilmCastData;
import io.micronaut.context.annotation.Prototype;
import jakarta.inject.Inject;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import viaduct.java.api.annotations.Resolver;
import viaduct.java.grts.Character;

/** Resolves a film's characters from shared cast backing data. */
@Resolver(objectValueFragment = "fragment _ on Film { castData }")
@Prototype
public final class FilmCharactersResolver extends FilmResolvers.Characters {
  private final CharacterRepository characterRepository;

  @Inject
  public FilmCharactersResolver(CharacterRepository characterRepository) {
    this.characterRepository = characterRepository;
  }

  @Override
  public CompletableFuture<List<Character>> resolve(Context context) {
    FilmCastData castData =
        (FilmCastData) context.getObjectValue().getJavaEngineObjectData().getOrNull("castData");
    List<Character> characters =
        castData.characterIds().stream()
            .map(
                id -> {
                  com.example.starwars.modules.filmography.characters.models.Character character =
                      characterRepository.findById(id);
                  if (character == null) {
                    throw new IllegalArgumentException("Character with ID " + id + " not found");
                  }
                  return new CharacterBuilder(context).build(character);
                })
            .toList();
    return CompletableFuture.completedFuture(characters);
  }
}
