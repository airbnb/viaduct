package com.example.starwars.modules.filmography.characters.queries;

import com.example.starwars.filmography.resolverbases.QueryResolvers;
import com.example.starwars.modules.filmography.characters.models.CharacterBuilder;
import com.example.starwars.modules.filmography.characters.models.CharacterRepository;
import io.micronaut.context.annotation.Prototype;
import jakarta.inject.Inject;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import viaduct.java.api.annotations.Resolver;
import viaduct.java.grts.Character;

/** Resolves characters with optional limit-based pagination. */
@Resolver
@Prototype
public final class AllCharactersQueryResolver extends QueryResolvers.AllCharacters {
  private static final int DEFAULT_PAGE_SIZE = 10;

  private final CharacterRepository characterRepository;

  @Inject
  public AllCharactersQueryResolver(CharacterRepository characterRepository) {
    this.characterRepository = characterRepository;
  }

  @Override
  public CompletableFuture<List<Character>> resolve(Context context) {
    Integer requestedLimit = context.getArguments().getLimit();
    int limit = requestedLimit == null ? DEFAULT_PAGE_SIZE : requestedLimit;
    List<Character> characters =
        characterRepository.findAll().stream()
            .limit(limit)
            .map(character -> new CharacterBuilder(context).build(character))
            .toList();
    return CompletableFuture.completedFuture(characters);
  }
}
