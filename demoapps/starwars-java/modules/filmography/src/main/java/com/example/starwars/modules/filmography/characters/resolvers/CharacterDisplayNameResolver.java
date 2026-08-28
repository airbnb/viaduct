package com.example.starwars.modules.filmography.characters.resolvers;

import com.example.starwars.filmography.resolverbases.CharacterResolvers;
import io.micronaut.context.annotation.Prototype;
import java.util.concurrent.CompletableFuture;
import viaduct.java.api.annotations.Resolver;

/** Demonstrates shorthand required-selection syntax. */
@Resolver(objectValueFragment = "name")
@Prototype
public final class CharacterDisplayNameResolver extends CharacterResolvers.DisplayName {
  @Override
  public CompletableFuture<String> resolve(Context context) {
    return CompletableFuture.completedFuture(context.getObjectValue().getName());
  }
}
