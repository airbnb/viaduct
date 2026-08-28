package com.example.starwars.modules.filmography.characters.mutations;

import com.example.starwars.common.SecurityAccessContext;
import com.example.starwars.filmography.resolverbases.MutationResolvers;
import com.example.starwars.modules.filmography.characters.models.CharacterFilmsRepository;
import com.example.starwars.modules.filmography.characters.models.CharacterRepository;
import com.example.starwars.modules.filmography.films.models.FilmCharactersRepository;
import io.micronaut.context.annotation.Prototype;
import jakarta.inject.Inject;
import java.util.concurrent.CompletableFuture;
import viaduct.java.api.annotations.Resolver;

/** Deletes a character and its film relationships with admin access. */
@Resolver
@Prototype
public final class DeleteCharacterMutation extends MutationResolvers.DeleteCharacter {
  private final CharacterRepository characterRepository;
  private final CharacterFilmsRepository characterFilmsRepository;
  private final FilmCharactersRepository filmCharactersRepository;

  @Inject
  public DeleteCharacterMutation(
      CharacterRepository characterRepository,
      CharacterFilmsRepository characterFilmsRepository,
      FilmCharactersRepository filmCharactersRepository) {
    this.characterRepository = characterRepository;
    this.characterFilmsRepository = characterFilmsRepository;
    this.filmCharactersRepository = filmCharactersRepository;
  }

  @Override
  public CompletableFuture<Boolean> resolve(Context context) {
    return CompletableFuture.completedFuture(
        SecurityAccessContext.from(context.getRequestContext())
            .validateAccess(
                () -> {
                  String id = context.getArguments().getId().getInternalID();
                  if (!characterRepository.delete(id)) {
                    throw new IllegalArgumentException("Character with ID " + id + " not found");
                  }
                  filmCharactersRepository.removeCharacter(id);
                  characterFilmsRepository.removeCharacter(id);
                  return true;
                }));
  }
}
