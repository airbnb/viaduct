package com.example.starwars.modules.universe.starships.models

import viaduct.api.context.ExecutionContext
import viaduct.api.context.globalIDFor

/**
 * A builder class for constructing [Starship] GraphQL objects from
 * [Starship] entities.
 *
 * @property ctx The execution context used for building the GraphQL object.
 */
class StarshipBuilder(private val ctx: ExecutionContext) {
    fun build(starship: Starship): viaduct.api.grts.Starship =
        // tag::global_id_example[3] Example using global IDs
        viaduct.api.grts.Starship.Builder(ctx)
            .id(ctx.globalIDFor<viaduct.api.grts.Starship>(starship.id))
            .name(starship.name)
            .model(starship.model)
            .starshipClass(starship.starshipClass)
            .manufacturers(starship.manufacturers)
            .costInCredits(starship.costInCredits?.toDouble())
            .length(starship.length?.toDouble())
            .crew(starship.crew)
            .passengers(starship.passengers)
            .maxAtmospheringSpeed(starship.maxAtmospheringSpeed)
            .hyperdriveRating(starship.hyperdriveRating?.toDouble())
            .MGLT(starship.mglt)
            .cargoCapacity(starship.cargoCapacity?.toDouble())
            .consumables(starship.consumables)
            .created(starship.created.toString())
            .edited(starship.edited.toString())
            .build()
}
