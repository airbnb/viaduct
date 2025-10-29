package com.example.starwars.modules.universe.vehicles.models

import jakarta.inject.Singleton

/**
 * In-memory Repository for Star Wars vehicles and related entities.
 * This serves as the backing data source for the GraphQL API.
 */
@Singleton
class VehiclesRepository {
    private val vehicles: List<Vehicle> = listOf(
        Vehicle(
            id = "1",
            name = "Speeder bike",
            model = "74-Z speeder bike",
            vehicleClass = "speeder",
            manufacturers = listOf("Aratech Repulsor Company"),
            costInCredits = 8000f,
            length = 3f,
            crew = "1",
            passengers = "1",
            maxAtmospheringSpeed = 360,
            cargoCapacity = 4f,
            consumables = "1 day"
        )
    )

    fun findAll() = vehicles

    fun findById(id: String) = vehicles.firstOrNull { it.id == id }
}
