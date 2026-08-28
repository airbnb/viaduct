package com.example.starwars.modules.filmography.characters.queries;

import com.example.starwars.filmography.resolverbases.QueryResolvers;
import com.example.starwars.modules.filmography.characters.operations.CharacterByNameOperation;
import io.micronaut.context.annotation.Prototype;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import viaduct.java.api.annotations.Resolver;

/** Executes a statically declared Java query operation from a resolver. */
@Resolver
@Prototype
public final class CharacterSummaryByNameQueryResolver
    extends QueryResolvers.CharacterSummaryByName {
  @Override
  public CompletableFuture<String> resolve(Context context) {
    return context
        .query(CharacterByNameOperation.INSTANCE, Map.of("name", context.getArguments().getName()))
        .thenApply(
            query -> {
              viaduct.java.grts.Character character = query.getSearchCharacter();
              if (character == null) {
                return null;
              }
              String name = character.getName() == null ? "Unknown" : character.getName();
              String birthYear =
                  character.getBirthYear() == null
                      ? "Unknown birth year"
                      : character.getBirthYear();
              return name + " (" + birthYear + ")";
            });
  }
}
