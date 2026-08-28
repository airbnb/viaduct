package com.example.starwars.modules.filmography.characters.resolvers;

import com.example.starwars.filmography.resolverbases.CharacterResolvers;
import io.micronaut.context.annotation.Prototype;
import java.util.concurrent.CompletableFuture;
import viaduct.java.api.annotations.Resolver;
import viaduct.java.api.annotations.Variable;

/** Uses an argument-backed variable to conditionally select profile details. */
@Resolver(
    objectValueFragment =
        """
        fragment _ on Character {
          name
          birthYear @include(if: $includeDetails)
          height @include(if: $includeDetails)
          mass @include(if: $includeDetails)
        }
        """,
    variables = @Variable(name = "includeDetails", fromArgument = "includeDetails"))
@Prototype
public final class ProfileFieldResolver extends CharacterResolvers.CharacterProfile {
  @Override
  public CompletableFuture<String> resolve(Context context) {
    viaduct.java.grts.Character character = context.getObjectValue();
    String name = character.getName() == null ? "Unknown" : character.getName();
    if (!context.getArguments().getIncludeDetails()) {
      return CompletableFuture.completedFuture("Character Profile: " + name + " (basic info only)");
    }
    StringBuilder result = new StringBuilder("Character Profile: ").append(name);
    if (character.getBirthYear() != null) {
      result.append(", Born: ").append(character.getBirthYear());
    }
    if (character.getHeight() != null) {
      result.append(", Height: ").append(character.getHeight()).append("cm");
    }
    if (character.getMass() != null) {
      result.append(", Mass: ").append(character.getMass()).append("kg");
    }
    return CompletableFuture.completedFuture(result.toString());
  }
}
