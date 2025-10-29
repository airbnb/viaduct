package com.example.starwars.modules.universe.species.models

import jakarta.inject.Singleton

/**
 * Repository for managing and retrieving species data.
 *
 * This is a mock implementation with hardcoded data for demonstration purposes.
 */
@Singleton
class SpeciesRepository {
    private val species = listOf(
        Species(
            id = "1",
            name = "Human",
            classification = "mammal",
            designation = "sentient",
            averageHeight = 180f,
            averageLifespan = 120,
            eyeColors = listOf("brown", "blue", "green", "hazel", "grey", "amber"),
            hairColors = listOf("blonde", "brown", "black", "red"),
            language = "Galactic Basic",
            homeworldId = "5",
            extrasData = SpeciesExtrasData(
                culturalNotes = "Diverse species with strong adaptability and technological advancement",
                rarityLevel = "Common",
                specialAbilities = listOf("Force sensitivity (rare)", "Adaptability", "Innovation"),
                technologicalLevel = "Advanced"
            )
        ),
        Species(
            id = "2",
            name = "Wookiee",
            classification = "mammal",
            designation = "sentient",
            averageHeight = 210f,
            averageLifespan = 400,
            eyeColors = listOf("blue", "brown", "green"),
            hairColors = listOf("brown", "black"),
            language = "Shyriiwook",
            homeworldId = "5"
        )
    )

    /**
     * Retrieves all species.
     *
     * @return A list of all species.
     */
    fun findAll(): List<Species> = species

    /**
     * Finds a species by its ID.
     *
     * @param id The ID of the species to find.
     * @return The species with the given ID, or null if not found.
     */
    fun findById(id: String): Species? =
        species.find {
            it.id == id
        }

    /**
     * Finds species by their homeworld ID.
     *
     * @param homeworldId The ID of the homeworld to filter species by.
     * @return A list of species that have the given homeworld ID.
     */
    fun findByHomeworldId(homeworldId: String): List<Species> =
        species.filter {
            it.homeworldId == homeworldId
        }
}
