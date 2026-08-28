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
import viaduct.java.grts.Species;

/** Resolves the unique species represented in a film's cast. */
@Resolver(objectValueFragment = "id")
@Prototype
public final class FilmSpeciesResolver extends FilmResolvers.Species {
  private final CharacterRepository characterRepository;
  private final FilmCharactersRepository filmCharactersRepository;

  @Inject
  public FilmSpeciesResolver(
      CharacterRepository characterRepository, FilmCharactersRepository filmCharactersRepository) {
    this.characterRepository = characterRepository;
    this.filmCharactersRepository = filmCharactersRepository;
  }

  @Override
  public CompletableFuture<List<Species>> resolve(Context context) {
    String filmId = context.getObjectValue().getId().getInternalID();
    LinkedHashSet<String> speciesIds = new LinkedHashSet<>();
    filmCharactersRepository
        .findCharactersByFilmId(filmId)
        .forEach(
            characterId -> {
              var character = characterRepository.findById(characterId);
              if (character != null && character.speciesId() != null) {
                speciesIds.add(character.speciesId());
              }
            });
    return CompletableFuture.completedFuture(
        speciesIds.stream()
            .map(id -> context.nodeRef(context.globalIDFor(Type.ofClass(Species.class), id)))
            .toList());
  }
}
