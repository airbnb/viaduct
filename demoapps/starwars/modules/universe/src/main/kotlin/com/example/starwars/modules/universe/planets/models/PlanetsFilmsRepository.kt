package com.example.starwars.modules.universe.planets.models

import jakarta.inject.Singleton

/**
 * Repository object for managing the relationship between planets and films in the Star Wars universe in memory.
 */
@Singleton
class PlanetsFilmsRepository {
    private val planetFilmRelations = mutableMapOf(
        "1" to mutableListOf("1", "2", "3"), // Luke in all three films
        "2" to mutableListOf("1", "2", "3"), // Leia in all three films
        "3" to mutableListOf("1", "2", "3"), // Han in all three films
        "4" to mutableListOf("1", "2", "3"), // Vader in all three films
        "5" to mutableListOf("1", "2", "3") // Obi-Wan in all three films
    )

    /**
     * Finds films by the given planet ID.
     *
     * @param planetId The ID of the planet.
     * @return A list of [PlanetFilms] representing the films associated with the planet.
     */
    fun findFilmsByPlanetId(planetId: String): List<PlanetFilms> {
        return (planetFilmRelations[planetId] ?: emptyList()).map {
            PlanetFilms(
                filmId = it,
                planetId = planetId,
            )
        }
    }
}
