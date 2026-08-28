package com.example.starwars.modules.filmography.characters.resolvers;

import com.example.starwars.filmography.resolverbases.CharacterResolvers;
import io.micronaut.context.annotation.Prototype;
import java.util.concurrent.CompletableFuture;
import viaduct.java.api.annotations.Resolver;

/** Computes a character appearance description from a required selection. */
@Resolver(objectValueFragment = "fragment _ on Character { name eyeColor hairColor }")
@Prototype
public final class CharacterAppearanceDescriptionResolver
    extends CharacterResolvers.AppearanceDescription {
  @Override
  public CompletableFuture<String> resolve(Context context) {
    viaduct.java.grts.Character character = context.getObjectValue();
    String name = character.getName() == null ? "Someone" : character.getName();
    String eyeColor = character.getEyeColor() == null ? "unknown eyes" : character.getEyeColor();
    String hairColor = character.getHairColor() == null ? "unknown hair" : character.getHairColor();
    return CompletableFuture.completedFuture(
        name + " has " + eyeColor + " eyes and " + hairColor + " hair");
  }
}
