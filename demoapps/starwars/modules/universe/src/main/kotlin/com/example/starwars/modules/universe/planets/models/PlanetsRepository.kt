package com.example.starwars.modules.universe.planets.models

import jakarta.inject.Singleton

// Data constants
const val UNKNOWN_DIAMETER = 0

/**
 * Repository object storing planet data in memory.
 */
@Singleton
class PlanetsRepository {
    private val planets: List<Planet> = listOf(
        Planet(
            id = "1",
            name = "Tatooine",
            diameter = 10465,
            rotationPeriod = 23,
            orbitalPeriod = 304,
            gravity = 1f,
            population = 200000f,
            climates = listOf("arid"),
            terrains = listOf("desert"),
            surfaceWater = 1f
        ),
        Planet(
            id = "2",
            name = "Alderaan",
            diameter = 12500,
            rotationPeriod = 24,
            orbitalPeriod = 364,
            gravity = 1f,
            population = 2000000000f,
            climates = listOf("temperate"),
            terrains = listOf("grasslands", "mountains"),
            surfaceWater = 40f
        ),
        Planet(
            id = "3",
            name = "Corellia",
            diameter = 11000,
            rotationPeriod = 25,
            orbitalPeriod = 329,
            gravity = 1f,
            population = 3000000000f,
            climates = listOf("temperate"),
            terrains = listOf("plains", "urban", "hills", "forests"),
            surfaceWater = 70f
        ),
        Planet(
            id = "4",
            name = "Stewjon",
            diameter = UNKNOWN_DIAMETER,
            rotationPeriod = null,
            orbitalPeriod = null,
            gravity = 1f,
            population = null,
            climates = listOf("temperate"),
            terrains = listOf("grass"),
            surfaceWater = null
        ),
        Planet(
            id = "5",
            name = "Earth",
            diameter = UNKNOWN_DIAMETER,
            rotationPeriod = 24,
            orbitalPeriod = 365,
            gravity = 9.8f,
            population = 8_000_000_000F,
            climates = listOf("temperate"),
            terrains = listOf("grass"),
            surfaceWater = 70F
        ),
        Planet(
            id = "6",
            name = "Kashyyyk",
            diameter = 12765,
            rotationPeriod = 26,
            orbitalPeriod = 381,
            gravity = 1f,
            population = 45000000f,
            climates = listOf("tropical"),
            terrains = listOf("jungle", "forest", "lakes"),
            surfaceWater = 60f
        )
    )

    /**
     * Returns all planets in the repository.
     */
    fun findAll() = planets

    /**
     * Finds a planet by its unique ID.
     *
     * @param id The unique identifier of the planet.
     * @return The planet with the matching ID, or null if not found.
     */
    fun findById(id: String) = planets.firstOrNull { it.id == id }
}
