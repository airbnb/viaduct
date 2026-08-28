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
import viaduct.java.grts.CreateCharacterInput;

/** Creates a character after validating request-scoped admin access. */
@Resolver
@Prototype
public final class CreateCharacterMutation extends MutationResolvers.CreateCharacter {
  private final CharacterRepository characterRepository;

  @Inject
  public CreateCharacterMutation(CharacterRepository characterRepository) {
    this.characterRepository = characterRepository;
  }

  @Override
  public CompletableFuture<viaduct.java.grts.Character> resolve(Context context) {
    return CompletableFuture.completedFuture(
        SecurityAccessContext.from(context.getRequestContext())
            .validateAccess(
                () -> {
                  CreateCharacterInput input = context.getArguments().getInput();
                  Character character =
                      characterRepository.add(
                          new Character(
                              "",
                              input.getName(),
                              input.getBirthYear(),
                              input.getEyeColor(),
                              input.getGender(),
                              input.getHairColor(),
                              input.getHeight(),
                              input.getMass() == null ? null : input.getMass().floatValue(),
                              input.getHomeworldId() == null
                                  ? null
                                  : input.getHomeworldId().getInternalID(),
                              input.getSpeciesId() == null
                                  ? null
                                  : input.getSpeciesId().getInternalID()));
                  return new CharacterBuilder(context).build(character);
                }));
  }
}
