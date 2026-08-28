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
import viaduct.java.grts.Species;

/** Batches character-to-species references. */
@Resolver(objectValueFragment = "fragment _ on Character { id }")
@Prototype
public final class CharacterSpeciesResolver extends CharacterResolvers.Species {
  private final CharacterRepository characterRepository;

  @Inject
  public CharacterSpeciesResolver(CharacterRepository characterRepository) {
    this.characterRepository = characterRepository;
  }

  @Override
  public CompletableFuture<Map<Context, Species>> batchResolve(List<Context> contexts) {
    Map<Context, Species> results = new IdentityHashMap<>();
    for (Context context : contexts) {
      Character character =
          characterRepository.findById(context.getObjectValue().getId().getInternalID());
      Species species =
          character == null || character.speciesId() == null
              ? null
              : context.nodeRef(
                  context.globalIDFor(Type.ofClass(Species.class), character.speciesId()));
      results.put(context, species);
    }
    return CompletableFuture.completedFuture(results);
  }
}
