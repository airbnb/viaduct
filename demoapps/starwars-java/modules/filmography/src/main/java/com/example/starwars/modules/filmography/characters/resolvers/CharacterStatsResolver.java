package com.example.starwars.modules.filmography.characters.resolvers;

import com.example.starwars.filmography.resolverbases.CharacterResolvers;
import io.micronaut.context.annotation.Prototype;
import java.util.concurrent.CompletableFuture;
import viaduct.java.api.annotations.Resolver;

/** Builds a character statistics summary from selected fields and arguments. */
@Resolver(
    objectValueFragment = "fragment _ on Character { name birthYear height species { name } }")
@Prototype
public final class CharacterStatsResolver extends CharacterResolvers.CharacterStats {
  @Override
  public CompletableFuture<String> resolve(Context context) {
    viaduct.java.grts.Character character = context.getObjectValue();
    String name = character.getName() == null ? "Unknown" : character.getName();
    StringBuilder result =
        new StringBuilder("Stats for ")
            .append(name)
            .append(" (Age range: ")
            .append(context.getArguments().getMinAge())
            .append('-')
            .append(context.getArguments().getMaxAge())
            .append(')');
    if (character.getBirthYear() != null) {
      result.append(", Born: ").append(character.getBirthYear());
    }
    if (character.getHeight() != null) {
      result.append(", Height: ").append(character.getHeight()).append("cm");
    }
    if (character.getSpecies() != null && character.getSpecies().getName() != null) {
      result.append(", Species: ").append(character.getSpecies().getName());
    }
    return CompletableFuture.completedFuture(result.toString());
  }
}
