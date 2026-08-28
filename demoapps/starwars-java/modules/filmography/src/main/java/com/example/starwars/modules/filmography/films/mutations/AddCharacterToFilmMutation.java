package com.example.starwars.modules.filmography.films.mutations;

import com.example.starwars.common.SecurityAccessContext;
import com.example.starwars.filmography.resolverbases.MutationResolvers;
import com.example.starwars.modules.filmography.characters.models.CharacterBuilder;
import com.example.starwars.modules.filmography.characters.models.CharacterFilmsRepository;
import com.example.starwars.modules.filmography.characters.models.CharacterRepository;
import com.example.starwars.modules.filmography.films.models.FilmBuilder;
import com.example.starwars.modules.filmography.films.models.FilmCharactersRepository;
import com.example.starwars.modules.filmography.films.models.FilmsRepository;
import io.micronaut.context.annotation.Prototype;
import jakarta.inject.Inject;
import java.util.concurrent.CompletableFuture;
import viaduct.java.api.annotations.Resolver;
import viaduct.java.grts.AddCharacterToFilmInput;
import viaduct.java.grts.AddCharacterToFilmPayload;

/** Links an existing character to an existing film with admin access. */
@Resolver
@Prototype
public final class AddCharacterToFilmMutation extends MutationResolvers.AddCharacterToFilm {
  private final CharacterFilmsRepository characterFilmsRepository;
  private final FilmCharactersRepository filmCharactersRepository;
  private final FilmsRepository filmsRepository;
  private final CharacterRepository characterRepository;

  @Inject
  public AddCharacterToFilmMutation(
      CharacterFilmsRepository characterFilmsRepository,
      FilmCharactersRepository filmCharactersRepository,
      FilmsRepository filmsRepository,
      CharacterRepository characterRepository) {
    this.characterFilmsRepository = characterFilmsRepository;
    this.filmCharactersRepository = filmCharactersRepository;
    this.filmsRepository = filmsRepository;
    this.characterRepository = characterRepository;
  }

  @Override
  public CompletableFuture<AddCharacterToFilmPayload> resolve(Context context) {
    return CompletableFuture.completedFuture(
        SecurityAccessContext.from(context.getRequestContext())
            .validateAccess(
                () -> {
                  AddCharacterToFilmInput input = context.getArguments().getInput();
                  String filmId = input.getFilmId().getInternalID();
                  if (input.getCharacterId() == null) {
                    throw new IllegalArgumentException("Character ID is required");
                  }
                  String characterId = input.getCharacterId().getInternalID();
                  var film = filmsRepository.findFilmById(filmId);
                  if (film == null) {
                    throw new IllegalArgumentException("Film with ID " + filmId + " not found");
                  }
                  var character = characterRepository.findById(characterId);
                  if (character == null) {
                    throw new IllegalArgumentException(
                        "Character with ID " + characterId + " not found");
                  }

                  characterFilmsRepository.addCharacterToFilm(characterId, filmId);
                  filmCharactersRepository.addCharacterToFilm(filmId, characterId);
                  return AddCharacterToFilmPayload.builder(context)
                      .film(new FilmBuilder(context).build(film))
                      .character(new CharacterBuilder(context).build(character))
                      .build();
                }));
  }
}
