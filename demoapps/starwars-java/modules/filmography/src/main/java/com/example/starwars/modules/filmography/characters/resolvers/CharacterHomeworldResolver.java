package com.example.starwars.modules.filmography.characters.resolvers;

import com.example.starwars.filmography.resolverbases.CharacterResolvers;
import com.example.starwars.modules.filmography.characters.models.Character;
import com.example.starwars.modules.filmography.characters.models.CharacterRepository;
import io.micronaut.context.annotation.Prototype;
import jakarta.inject.Inject;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import viaduct.java.api.annotations.Resolver;
import viaduct.java.api.reflect.Type;
import viaduct.java.grts.Planet;

/** Batches character-to-homeworld references. */
@Resolver(objectValueFragment = "fragment _ on Character { id }")
@Prototype
public final class CharacterHomeworldResolver extends CharacterResolvers.Homeworld {
  private final CharacterRepository characterRepository;

  @Inject
  public CharacterHomeworldResolver(CharacterRepository characterRepository) {
    this.characterRepository = characterRepository;
  }

  @Override
  public CompletableFuture<Map<Context, Planet>> batchResolve(List<Context> contexts) {
    Map<Context, Planet> results = new IdentityHashMap<>();
    for (Context context : contexts) {
      Character character =
          characterRepository.findById(context.getObjectValue().getId().getInternalID());
      Planet planet =
          character == null || character.homeworldId() == null
              ? null
              : context.nodeRef(
                  context.globalIDFor(Type.ofClass(Planet.class), character.homeworldId()));
      results.put(context, planet);
    }
    return CompletableFuture.completedFuture(results);
  }
}
