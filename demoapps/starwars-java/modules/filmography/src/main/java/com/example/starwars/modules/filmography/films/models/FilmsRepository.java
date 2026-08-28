package com.example.starwars.modules.filmography.films.models;

import jakarta.inject.Singleton;
import java.util.List;

/** In-memory film repository used by the demo resolvers. */
@Singleton
public final class FilmsRepository {
  private final List<Film> films =
      List.of(
          new Film(
              "1",
              "A New Hope",
              4,
              """
              It is a period of civil war.
              Rebel spaceships, striking
              from a hidden base, have won
              their first victory against
              the evil Galactic Empire.
              """
                  .stripIndent()
                  .trim(),
              "George Lucas",
              List.of("Gary Kurtz", "Rick McCallum"),
              "1977-05-25"),
          new Film(
              "2",
              "The Empire Strikes Back",
              5,
              """
              It is a dark time for the
              Rebellion. Although the Death
              Star has been destroyed,
              Imperial troops have driven the
              Rebel forces from their hidden
              base and pursued them across
              the galaxy.
              """
                  .stripIndent()
                  .trim(),
              "Irvin Kershner",
              List.of("Gary Kurtz"),
              "1980-05-17"),
          new Film(
              "3",
              "Return of the Jedi",
              6,
              """
              Luke Skywalker has returned to
              his home planet of Tatooine in
              an attempt to rescue his
              friend Han Solo from the
              clutches of the vile gangster
              Jabba the Hutt.
              """
                  .stripIndent()
                  .trim(),
              "Richard Marquand",
              List.of("Howard G. Kazanjian", "George Lucas", "Rick McCallum"),
              "1983-05-25"));

  public List<Film> getAllFilms() {
    return films;
  }

  public Film findFilmById(String filmId) {
    return films.stream().filter(film -> film.id().equals(filmId)).findFirst().orElse(null);
  }
}
