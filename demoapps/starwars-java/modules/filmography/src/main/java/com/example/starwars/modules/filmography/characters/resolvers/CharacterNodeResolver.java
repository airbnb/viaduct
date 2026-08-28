package com.example.starwars.modules.filmography.characters.resolvers;

import com.example.starwars.filmography.resolverbases.NodeResolvers;
import com.example.starwars.modules.filmography.characters.models.CharacterBuilder;
import com.example.starwars.modules.filmography.characters.models.CharacterRepository;
import io.micronaut.context.annotation.Prototype;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import viaduct.java.api.annotations.Resolver;
import viaduct.java.api.resolvers.FieldValue;
import viaduct.java.grts.Character;

/** Batched node resolver for characters. */
@Resolver
@Prototype
public final class CharacterNodeResolver extends NodeResolvers.Character {
  private final CharacterRepository characterRepository;

  @Inject
  public CharacterNodeResolver(CharacterRepository characterRepository) {
    this.characterRepository = characterRepository;
  }

  @Override
  public CompletableFuture<Map<Context, FieldValue<Character>>> batchResolve(
      List<Context> contexts) {
    Map<Context, FieldValue<Character>> results = new LinkedHashMap<>();
    for (Context context : contexts) {
      String characterId = context.getId().getInternalID();
      com.example.starwars.modules.filmography.characters.models.Character character =
          characterRepository.findById(characterId);
      results.put(
          context,
          character == null
              ? FieldValue.ofError(
                  new IllegalArgumentException("Character not found: " + characterId))
              : FieldValue.ofValue(new CharacterBuilder(context).build(character)));
    }
    return CompletableFuture.completedFuture(results);
  }
}
