package com.example.starwars.modules.universe.species.models

/**
 * Data class representing extra data for a species, used in the "extras" scope.
 *
 * @property culturalNotes Additional cultural information about the species.
 * @property rarityLevel The rarity level of the species (e.g., common, rare).
 * @property specialAbilities List of special abilities or traits of the species.
 * @property technologicalLevel The technological level of the species' civilization.
 */
data class SpeciesExtrasData(
    val culturalNotes: String? = null,
    val rarityLevel: String? = null,
    val specialAbilities: List<String> = emptyList(),
    val technologicalLevel: String? = null
)
