package com.example.starwars.modules.filmography.characters.resolvers;

import com.example.starwars.filmography.resolverbases.CharacterResolvers;
import io.micronaut.context.annotation.Prototype;
import java.util.concurrent.CompletableFuture;
import viaduct.java.api.annotations.Resolver;

/** Computes adulthood from a BBY/ABY birth-year value. */
@Resolver(objectValueFragment = "fragment _ on Character { birthYear }")
@Prototype
public final class CharacterIsAdultResolver extends CharacterResolvers.IsAdult {
  @Override
  public CompletableFuture<Boolean> resolve(Context context) {
    String birthYear = context.getObjectValue().getBirthYear();
    boolean isAdult =
        birthYear != null
            && Double.parseDouble(birthYear.substring(0, birthYear.length() - 3)) > 21;
    return CompletableFuture.completedFuture(isAdult);
  }
}
