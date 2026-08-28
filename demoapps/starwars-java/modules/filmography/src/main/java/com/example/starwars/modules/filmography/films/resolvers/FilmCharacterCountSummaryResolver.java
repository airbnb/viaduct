package com.example.starwars.modules.filmography.films.resolvers;

import com.example.starwars.filmography.resolverbases.FilmResolvers;
import com.example.starwars.modules.filmography.films.models.FilmCastData;
import io.micronaut.context.annotation.Prototype;
import java.util.concurrent.CompletableFuture;
import viaduct.java.api.annotations.Resolver;

/** Summarizes cast size using shared film backing data. */
@Resolver(objectValueFragment = "fragment _ on Film { title castData }")
@Prototype
public final class FilmCharacterCountSummaryResolver extends FilmResolvers.CharacterCountSummary {
  @Override
  public CompletableFuture<String> resolve(Context context) {
    viaduct.java.grts.Film film = context.getObjectValue();
    FilmCastData castData = castData(film);
    return CompletableFuture.completedFuture(
        film.getTitle() + " features " + castData.characterIds().size() + " main characters");
  }

  private FilmCastData castData(viaduct.java.grts.Film film) {
    return (FilmCastData) film.getJavaEngineObjectData().getOrNull("castData");
  }
}
