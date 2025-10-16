package com.example.starwars.modules.universe.vehicles.models

import java.time.Instant

/**
 * Entity class representing a vehicle in the Star Wars universe.
 */
data class Vehicle(
    val id: String,
    val name: String,
    val model: String?,
    val vehicleClass: String?,
    val manufacturers: List<String>,
    val costInCredits: Float?,
    val length: Float?,
    val crew: String?,
    val passengers: String?,
    val maxAtmospheringSpeed: Int?,
    val cargoCapacity: Float?,
    val consumables: String?,
    val created: Instant = Instant.now(),
    val edited: Instant = Instant.now()
)
