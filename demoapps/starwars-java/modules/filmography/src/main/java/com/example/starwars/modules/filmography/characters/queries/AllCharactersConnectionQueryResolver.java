package com.example.starwars.modules.filmography.characters.queries;

import com.example.starwars.filmography.resolverbases.QueryResolvers;
import com.example.starwars.modules.filmography.characters.models.CharacterBuilder;
import com.example.starwars.modules.filmography.characters.models.CharacterRepository;
import io.micronaut.context.annotation.Prototype;
import jakarta.inject.Inject;
import java.util.concurrent.CompletableFuture;
import viaduct.java.api.annotations.Resolver;
import viaduct.java.grts.CharactersConnection;

/** Resolves Relay-style character pagination. */
@Resolver
@Prototype
public final class AllCharactersConnectionQueryResolver
    extends QueryResolvers.AllCharactersConnection {
  private final CharacterRepository characterRepository;

  @Inject
  public AllCharactersConnectionQueryResolver(CharacterRepository characterRepository) {
    this.characterRepository = characterRepository;
  }

  @Override
  public CompletableFuture<CharactersConnection> resolve(Context context) {
    return CompletableFuture.completedFuture(
        CharactersConnection.builder(context)
            .fromList(
                characterRepository.findAll(),
                character -> new CharacterBuilder(context).build(character))
            .build());
  }
}
