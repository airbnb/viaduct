package com.example.starwars.modules.filmography.films.resolvers;

import com.example.starwars.filmography.resolverbases.FilmResolvers;
import com.example.starwars.modules.filmography.films.models.FilmCastData;
import com.example.starwars.modules.filmography.films.models.FilmCharactersRepository;
import io.micronaut.context.annotation.Prototype;
import jakarta.inject.Inject;
import java.util.concurrent.CompletableFuture;
import viaduct.java.api.annotations.Resolver;

/** Resolves cast IDs once as backing data shared by film field resolvers. */
@Resolver(objectValueFragment = "fragment _ on Film { id }")
@Prototype
public final class FilmCastDataResolver extends FilmResolvers.CastData {
  private final FilmCharactersRepository filmCharactersRepository;

  @Inject
  public FilmCastDataResolver(FilmCharactersRepository filmCharactersRepository) {
    this.filmCharactersRepository = filmCharactersRepository;
  }

  @Override
  public CompletableFuture<Object> resolve(Context context) {
    String filmId = context.getObjectValue().getId().getInternalID();
    return CompletableFuture.completedFuture(
        new FilmCastData(filmCharactersRepository.findCharactersByFilmId(filmId)));
  }
}
