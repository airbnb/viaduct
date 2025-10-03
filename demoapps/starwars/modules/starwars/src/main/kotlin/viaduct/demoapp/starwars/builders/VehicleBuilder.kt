package viaduct.demoapp.starwars.builders

import viaduct.api.context.ExecutionContext
import viaduct.api.grts.Vehicle
import viaduct.demoapp.starwars.data.StarWarsData

class VehicleBuilder(private val ctx: ExecutionContext) {
    fun build(vehicle: StarWarsData.Vehicle): Vehicle =
        Vehicle.Builder(ctx)
            .id(ctx.globalIDFor(Vehicle.Reflection, vehicle.id))
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
