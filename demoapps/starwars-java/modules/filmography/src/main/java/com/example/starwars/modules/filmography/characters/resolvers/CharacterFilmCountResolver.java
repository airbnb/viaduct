package com.example.starwars.modules.filmography.characters.resolvers;

import com.example.starwars.filmography.resolverbases.CharacterResolvers;
import com.example.starwars.modules.filmography.characters.models.CharacterFilmsRepository;
import io.micronaut.context.annotation.Prototype;
import jakarta.inject.Inject;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import viaduct.java.api.annotations.Resolver;

/** Batches film-count aggregation across character parents. */
@Resolver(objectValueFragment = "fragment _ on Character { id }")
@Prototype
public final class CharacterFilmCountResolver extends CharacterResolvers.FilmCount {
  private final CharacterFilmsRepository characterFilmsRepository;

  @Inject
  public CharacterFilmCountResolver(CharacterFilmsRepository characterFilmsRepository) {
    this.characterFilmsRepository = characterFilmsRepository;
  }

  @Override
  public CompletableFuture<Map<Context, Integer>> batchResolve(List<Context> contexts) {
    Map<Context, Integer> results = new IdentityHashMap<>();
    for (Context context : contexts) {
      String characterId = context.getObjectValue().getId().getInternalID();
      results.put(context, characterFilmsRepository.findFilmsByCharacterId(characterId).size());
    }
    return CompletableFuture.completedFuture(results);
  }
}
