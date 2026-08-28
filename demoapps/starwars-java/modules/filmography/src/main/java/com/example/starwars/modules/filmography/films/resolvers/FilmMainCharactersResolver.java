package com.example.starwars.modules.filmography.films.resolvers;

import com.example.starwars.filmography.resolverbases.FilmResolvers;
import com.example.starwars.modules.filmography.characters.models.CharacterBuilder;
import com.example.starwars.modules.filmography.characters.models.CharacterRepository;
import com.example.starwars.modules.filmography.films.models.FilmCharactersRepository;
import io.micronaut.context.annotation.Prototype;
import jakarta.inject.Inject;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import viaduct.java.api.annotations.Resolver;
import viaduct.java.grts.Character;

/** Batches reverse film-to-character relationships. */
@Resolver(objectValueFragment = "fragment _ on Film { id }")
@Prototype
public final class FilmMainCharactersResolver extends FilmResolvers.MainCharacters {
  private final CharacterRepository characterRepository;
  private final FilmCharactersRepository filmCharactersRepository;

  @Inject
  public FilmMainCharactersResolver(
      CharacterRepository characterRepository, FilmCharactersRepository filmCharactersRepository) {
    this.characterRepository = characterRepository;
    this.filmCharactersRepository = filmCharactersRepository;
  }

  @Override
  public CompletableFuture<Map<Context, List<Character>>> batchResolve(List<Context> contexts) {
    Map<Context, List<Character>> results = new IdentityHashMap<>();
    for (Context context : contexts) {
      String filmId = context.getObjectValue().getId().getInternalID();
      List<Character> characters =
          filmCharactersRepository.findCharactersByFilmId(filmId).stream()
              .map(characterRepository::findById)
              .filter(java.util.Objects::nonNull)
              .map(character -> new CharacterBuilder(context).build(character))
              .toList();
      results.put(context, characters);
    }
    return CompletableFuture.completedFuture(results);
  }
}
