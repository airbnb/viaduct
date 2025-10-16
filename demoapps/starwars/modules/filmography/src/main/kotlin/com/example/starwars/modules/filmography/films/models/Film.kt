package com.example.starwars.modules.filmography.films.models

import java.time.Instant

data class Film(
    val id: String,
    val title: String,
    val episodeID: Int,
    val openingCrawl: String,
    val director: String,
    val producers: List<String>,
    val releaseDate: String,
    val created: Instant = Instant.now(),
    val edited: Instant = Instant.now()
)
