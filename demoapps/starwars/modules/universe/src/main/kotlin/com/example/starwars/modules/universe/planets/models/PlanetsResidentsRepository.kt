package com.example.starwars.modules.universe.planets.models

import jakarta.inject.Singleton

/**
 * Repository for managing the association between planets and their residents (characters).
 */
@Singleton
class PlanetsResidentsRepository {
    val planetResidents = mapOf(
        "1" to listOf("1", "4"), // Tatooine has Luke and Vader
        "2" to listOf("2"), // Alderaan has Leia
        "3" to listOf("3"), // Corellia has Han
        "4" to listOf("5") // Stewjon has Obi-Wan
    )

    /**
     * Finds residents (characters) by the given planet ID.
     *
     * @param planetId The ID of the planet.
     * @return A list of [PlanetCharacter] representing the residents of the planet.
     */
    fun findResidentsByPlanetId(planetId: String): List<PlanetCharacter> {
        return (planetResidents[planetId] ?: emptyList()).map {
            PlanetCharacter(
                characterId = it,
                planetId = planetId,
            )
        }
    }
}
