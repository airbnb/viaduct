package com.example.starwars.modules.universe.planets.models

import java.time.Instant

/**
 * Entity class representing a planet in the Star Wars universe.
 */
data class Planet(
    val id: String,
    val name: String,
    val diameter: Int?,
    val rotationPeriod: Int?,
    val orbitalPeriod: Int?,
    val gravity: Float?,
    val population: Float?,
    val surfaceWater: Float?,
    val climates: List<String>,
    val terrains: List<String>,
    val created: Instant = Instant.now(),
    val edited: Instant = Instant.now()
)
