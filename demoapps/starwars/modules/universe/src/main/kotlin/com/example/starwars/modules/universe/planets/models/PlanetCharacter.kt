package com.example.starwars.modules.universe.planets.models

/**
 * Entity class representing the association between a character and a planet in the Star Wars universe.
 */
data class PlanetCharacter(
    val characterId: String,
    val planetId: String,
)
