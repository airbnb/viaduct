package com.example.starwars.modules.universe.starships.models

import java.time.Instant

/**
 * Data class representing a starship entity in the Star Wars universe.
 */
data class Starship(
    val id: String,
    val name: String,
    val model: String?,
    val starshipClass: String?,
    val manufacturers: List<String>,
    val costInCredits: Float?,
    val length: Float?,
    val crew: String?,
    val passengers: String?,
    val maxAtmospheringSpeed: Int?,
    val hyperdriveRating: Float?,
    val mglt: Int?,
    val cargoCapacity: Float?,
    val consumables: String?,
    val created: Instant = Instant.now(),
    val edited: Instant = Instant.now()
)
