package com.example.starwars.modules.filmography.films.resolvers;

import com.example.starwars.filmography.resolverbases.FilmResolvers;
import com.example.starwars.modules.filmography.characters.models.CharacterRepository;
import com.example.starwars.modules.filmography.films.models.FilmCharactersRepository;
import io.micronaut.context.annotation.Prototype;
import jakarta.inject.Inject;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import viaduct.java.api.annotations.Resolver;
import viaduct.java.api.reflect.Type;
import viaduct.java.grts.Planet;

/** Resolves the unique homeworlds represented in a film's cast. */
@Resolver(objectValueFragment = "id")
@Prototype
public final class FilmPlanetsResolver extends FilmResolvers.Planets {
  private final CharacterRepository characterRepository;
  private final FilmCharactersRepository filmCharactersRepository;

  @Inject
  public FilmPlanetsResolver(
      CharacterRepository characterRepository, FilmCharactersRepository filmCharactersRepository) {
    this.characterRepository = characterRepository;
    this.filmCharactersRepository = filmCharactersRepository;
  }

  @Override
  public CompletableFuture<List<Planet>> resolve(Context context) {
    String filmId = context.getObjectValue().getId().getInternalID();
    LinkedHashSet<String> planetIds = new LinkedHashSet<>();
    filmCharactersRepository
        .findCharactersByFilmId(filmId)
        .forEach(
            characterId -> {
              var character = characterRepository.findById(characterId);
              if (character != null && character.homeworldId() != null) {
                planetIds.add(character.homeworldId());
              }
            });
    return CompletableFuture.completedFuture(
        planetIds.stream()
            .map(id -> context.nodeRef(context.globalIDFor(Type.ofClass(Planet.class), id)))
            .toList());
  }
}
