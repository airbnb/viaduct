package com.example.starwars.modules.filmography.characters.resolvers;

import com.example.starwars.filmography.resolverbases.CharacterResolvers;
import io.micronaut.context.annotation.Prototype;
import java.util.concurrent.CompletableFuture;
import viaduct.java.api.annotations.Resolver;

/** Demonstrates a named fragment in a Java resolver selection. */
@Resolver(objectValueFragment = "fragment _ on Character { ...CharacterIdentityFields }")
@Prototype
public final class CharacterDisplaySummaryResolver extends CharacterResolvers.DisplaySummary {
  @Override
  public CompletableFuture<String> resolve(Context context) {
    viaduct.java.grts.Character character = context.getObjectValue();
    String name = character.getName() == null ? "Unknown" : character.getName();
    String birthYear =
        character.getBirthYear() == null ? "Unknown birth year" : character.getBirthYear();
    return CompletableFuture.completedFuture(name + " (" + birthYear + ")");
  }
}
