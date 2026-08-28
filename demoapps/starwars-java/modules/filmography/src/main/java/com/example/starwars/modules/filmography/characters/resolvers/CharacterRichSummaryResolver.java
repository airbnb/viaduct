package com.example.starwars.modules.filmography.characters.resolvers;

import com.example.starwars.filmography.resolverbases.CharacterResolvers;
import com.example.starwars.modules.filmography.characters.models.Character;
import com.example.starwars.modules.filmography.characters.models.CharacterFilmsRepository;
import com.example.starwars.modules.filmography.characters.models.CharacterRepository;
import io.micronaut.context.annotation.Prototype;
import jakarta.inject.Inject;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import viaduct.java.api.annotations.Resolver;

/** Batches a summary that combines selected fields with repository data. */
@Resolver(objectValueFragment = "fragment _ on Character { id ...CharacterIdentityFields }")
@Prototype
public final class CharacterRichSummaryResolver extends CharacterResolvers.RichSummary {
  private final CharacterRepository characterRepository;
  private final CharacterFilmsRepository characterFilmsRepository;

  @Inject
  public CharacterRichSummaryResolver(
      CharacterRepository characterRepository, CharacterFilmsRepository characterFilmsRepository) {
    this.characterRepository = characterRepository;
    this.characterFilmsRepository = characterFilmsRepository;
  }

  @Override
  public CompletableFuture<Map<Context, String>> batchResolve(List<Context> contexts) {
    Map<Context, String> results = new IdentityHashMap<>();
    for (Context context : contexts) {
      viaduct.java.grts.Character objectValue = context.getObjectValue();
      String characterId = objectValue.getId().getInternalID();
      Character character = characterRepository.findById(characterId);
      String name = objectValue.getName() == null ? "Unknown" : objectValue.getName();
      String birthYear =
          objectValue.getBirthYear() == null ? "Unknown" : objectValue.getBirthYear();
      String homeworld =
          character == null || character.homeworldId() == null ? "Unknown world" : "TODO";
      int filmCount = characterFilmsRepository.findFilmsByCharacterId(characterId).size();
      results.put(
          context,
          name + " (" + birthYear + ") from " + homeworld + ", appears in " + filmCount + " films");
    }
    return CompletableFuture.completedFuture(results);
  }
}
