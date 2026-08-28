package com.example.starwars.modules.filmography.characters.resolvers;

import com.example.starwars.filmography.resolverbases.CharacterResolvers;
import io.micronaut.context.annotation.Prototype;
import java.util.concurrent.CompletableFuture;
import viaduct.java.api.annotations.Resolver;

/** Formats character details using two reusable named fragments. */
@Resolver(
    objectValueFragment =
        """
        fragment _ on Character {
          ...CharacterIdentityFields
          ...CharacterAppearanceFields
        }
        """)
@Prototype
public final class CharacterFormattedDescriptionResolver
    extends CharacterResolvers.FormattedDescription {
  @Override
  public CompletableFuture<String> resolve(Context context) {
    viaduct.java.grts.Character character = context.getObjectValue();
    String name = character.getName() == null ? "Unknown" : character.getName();
    String format = context.getArguments().getFormat();
    return CompletableFuture.completedFuture(
        switch (format) {
          case "detailed" ->
              appendAppearance(appendBirthYear(name, character.getBirthYear()), character);
          case "year-only" ->
              character.getBirthYear() == null
                  ? name + " (birth year unknown)"
                  : appendBirthYear(name, character.getBirthYear());
          case "appearance-only" -> appendAppearance(name, character);
          default -> name;
        });
  }

  private String appendBirthYear(String value, String birthYear) {
    return birthYear == null ? value : value + " (born " + birthYear + ")";
  }

  private String appendAppearance(String value, viaduct.java.grts.Character character) {
    if (character.getEyeColor() == null && character.getHairColor() == null) {
      return value;
    }
    StringBuilder result = new StringBuilder(value).append(" - ");
    if (character.getEyeColor() != null) {
      result.append(character.getEyeColor()).append(" eyes");
    }
    if (character.getEyeColor() != null && character.getHairColor() != null) {
      result.append(", ");
    }
    if (character.getHairColor() != null) {
      result.append(character.getHairColor()).append(" hair");
    }
    return result.toString();
  }
}
