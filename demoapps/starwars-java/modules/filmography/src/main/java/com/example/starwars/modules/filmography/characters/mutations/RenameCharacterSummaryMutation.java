package com.example.starwars.modules.filmography.characters.mutations;

import com.example.starwars.filmography.resolverbases.MutationResolvers;
import com.example.starwars.modules.filmography.characters.operations.RenameCharacterOperation;
import io.micronaut.context.annotation.Prototype;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import viaduct.java.api.annotations.Resolver;

/** Executes a statically declared Java mutation operation from a resolver. */
@Resolver
@Prototype
public final class RenameCharacterSummaryMutation extends MutationResolvers.RenameCharacterSummary {
  @Override
  public CompletableFuture<String> resolve(Context context) {
    return context
        .mutation(
            RenameCharacterOperation.INSTANCE,
            Map.of(
                "id", context.getArguments().getId(),
                "name", context.getArguments().getName()))
        .thenApply(
            mutation -> {
              viaduct.java.grts.Character character = mutation.getUpdateCharacterName();
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
