package com.example.starwars.modules.filmography.characters.queries;

import com.example.starwars.filmography.resolverbases.QueryResolvers;
import com.example.starwars.modules.filmography.characters.models.CharacterBuilder;
import com.example.starwars.modules.filmography.characters.models.CharacterRepository;
import io.micronaut.context.annotation.Prototype;
import jakarta.inject.Inject;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import viaduct.java.api.annotations.Resolver;
import viaduct.java.grts.CharacterSearchInput;

/** Searches characters by name, global ID, or birth year. */
@Resolver
@Prototype
public final class SearchCharacterQueryResolver extends QueryResolvers.SearchCharacter {
  private final CharacterRepository characterRepository;

  @Inject
  public SearchCharacterQueryResolver(CharacterRepository characterRepository) {
    this.characterRepository = characterRepository;
  }

  @Override
  public CompletableFuture<viaduct.java.grts.Character> resolve(Context context) {
    CharacterSearchInput search = context.getArguments().getSearch();
    com.example.starwars.modules.filmography.characters.models.Character character = null;
    if (search.getByName() != null) {
      List<com.example.starwars.modules.filmography.characters.models.Character> matches =
          characterRepository.findCharactersByName(search.getByName());
      character = matches.isEmpty() ? null : matches.get(0);
    } else if (search.getById() != null) {
      character = characterRepository.findById(search.getById().getInternalID());
    } else if (search.getByBirthYear() != null) {
      List<com.example.starwars.modules.filmography.characters.models.Character> matches =
          characterRepository.findCharactersByYearOfBirth(search.getByBirthYear());
      character = matches.isEmpty() ? null : matches.get(0);
    }
    return CompletableFuture.completedFuture(
        character == null ? null : new CharacterBuilder(context).build(character));
  }
}
