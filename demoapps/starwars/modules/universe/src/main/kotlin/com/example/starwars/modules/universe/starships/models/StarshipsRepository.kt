package com.example.starwars.modules.universe.starships.models

import jakarta.inject.Singleton

/**
 * In-memory data store for Star Wars starships.
 *
 * This serves as the backing data source for the starships GraphQL API.
 */
@Singleton
class StarshipsRepository {
    private val starships = listOf(
        Starship(
            id = "1",
            name = "Millennium Falcon",
            model = "YT-1300 light freighter",
            starshipClass = "Light freighter",
            manufacturers = listOf("Corellian Engineering Corporation"),
            costInCredits = 100000f,
            length = 34.37f,
            crew = "4",
            passengers = "6",
            maxAtmospheringSpeed = 1050,
            hyperdriveRating = 0.5f,
            mglt = 75,
            cargoCapacity = 100000f,
            consumables = "2 months"
        ),
        Starship(
            id = "2",
            name = "X-wing",
            model = "T-65 X-wing",
            starshipClass = "Starfighter",
            manufacturers = listOf("Incom Corporation"),
            costInCredits = 149999f,
            length = 12.5f,
            crew = "1",
            passengers = "0",
            maxAtmospheringSpeed = 1050,
            hyperdriveRating = 1.0f,
            mglt = 100,
            cargoCapacity = 110f,
            consumables = "1 week"
        )
    )

    /**
     * Retrieves all starships.
     *
     * @return A list of all [Starship] entities.
     */
    fun findAll(): List<Starship> = starships

    /**
     * Finds a starship by its ID.
     *
     * @param id The ID of the starship to find.
     * @return The [Starship] with the given ID, or null if not found.
     */
    fun findById(id: String): Starship? =
        starships.find {
            it.id == id
        }
}
