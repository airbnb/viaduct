package com.example.starwars.modules.filmography.films.models;

import java.time.Instant;
import java.util.List;

/** Film entity stored by the demo repository. */
public record Film(
    String id,
    String title,
    int episodeID,
    String openingCrawl,
    String director,
    List<String> producers,
    String releaseDate,
    Instant created,
    Instant edited) {

  public Film(
      String id,
      String title,
      int episodeID,
      String openingCrawl,
      String director,
      List<String> producers,
      String releaseDate) {
    this(
        id,
        title,
        episodeID,
        openingCrawl,
        director,
        List.copyOf(producers),
        releaseDate,
        Instant.now(),
        Instant.now());
  }
}
