package com.example.starwars.modules.universe.starships.models

/**
 * Repository for managing starship-pilot relationships.
 *
 * This is a simple in-memory representation.
 */
object StarshipPilotsRepository {
    private val starshipPilotRelations = mapOf(
        "1" to listOf("3"), // Millennium Falcon piloted by Han Solo
        "2" to listOf("1") // X-wing piloted by Luke Skywalker
    )

    /**
     * Finds pilot IDs by the given starship ID.
     *
     * @param starshipId The ID of the starship.
     * @return A list of pilot IDs associated with the starship.
     */
    fun findPilotsByStarshipId(starshipId: String): List<String> {
        return starshipPilotRelations[starshipId] ?: emptyList()
    }
}
