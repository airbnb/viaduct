package com.example.starwars.modules.filmography.characters.mutations;

import com.example.starwars.common.SecurityAccessContext;
import com.example.starwars.filmography.resolverbases.MutationResolvers;
import com.example.starwars.modules.filmography.characters.models.Character;
import com.example.starwars.modules.filmography.characters.models.CharacterBuilder;
import com.example.starwars.modules.filmography.characters.models.CharacterRepository;
import io.micronaut.context.annotation.Prototype;
import jakarta.inject.Inject;
import java.util.concurrent.CompletableFuture;
import viaduct.java.api.annotations.Resolver;

/** Renames a character after validating request-scoped admin access. */
@Resolver
@Prototype
public final class UpdateCharacterNameMutation extends MutationResolvers.UpdateCharacterName {
  private final CharacterRepository characterRepository;

  @Inject
  public UpdateCharacterNameMutation(CharacterRepository characterRepository) {
    this.characterRepository = characterRepository;
  }

  @Override
  public CompletableFuture<viaduct.java.grts.Character> resolve(Context context) {
    return CompletableFuture.completedFuture(
        SecurityAccessContext.from(context.getRequestContext())
            .validateAccess(
                () -> {
                  String id = context.getArguments().getId().getInternalID();
                  Character character = characterRepository.findById(id);
                  if (character == null) {
                    throw new IllegalArgumentException("Character with ID " + id + " not found");
                  }
                  return new CharacterBuilder(context)
                      .build(
                          characterRepository.update(
                              character.withName(context.getArguments().getName())));
                }));
  }
}
