package com.example.starwars.modules.universe.vehicles.models

import viaduct.api.context.ExecutionContext
import viaduct.api.context.globalIDFor

/**
 * Builder class to map viaduct Vehicle from the Vehicle entity.
 *
 * @property ctx The execution context used for building the `Vehicle` object.
 */
class VehicleBuilder(private val ctx: ExecutionContext) {
    fun build(vehicle: Vehicle): viaduct.api.grts.Vehicle =
        viaduct.api.grts.Vehicle.Builder(ctx)
            .id(ctx.globalIDFor<viaduct.api.grts.Vehicle>(vehicle.id))
            .name(vehicle.name)
            .model(vehicle.model)
            .vehicleClass(vehicle.vehicleClass)
            .manufacturers(vehicle.manufacturers)
            .costInCredits(vehicle.costInCredits?.toDouble())
            .length(vehicle.length?.toDouble())
            .crew(vehicle.crew)
            .passengers(vehicle.passengers)
            .maxAtmospheringSpeed(vehicle.maxAtmospheringSpeed)
            .cargoCapacity(vehicle.cargoCapacity?.toDouble())
            .consumables(vehicle.consumables)
            .created(vehicle.created.toString())
            .edited(vehicle.edited.toString())
            .build()
}
