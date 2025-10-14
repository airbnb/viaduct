package viaduct.demoapp.universe.starships.viaduct.mappers

import viaduct.api.context.ExecutionContext
import viaduct.api.grts.Starship

/**
 * A builder class for constructing [Starship] GraphQL objects from
 * [viaduct.demoapp.universe.starships.models.entities.Starship] entities.
 *
 * @property ctx The execution context used for building the GraphQL object.
 */
class StarshipBuilder(private val ctx: ExecutionContext) {
    fun build(starship: viaduct.demoapp.universe.starships.models.entities.Starship): Starship =
        // tag::global_id_example[3] Example using global IDs
        Starship.Builder(ctx)
            .id(ctx.globalIDFor(Starship.Reflection, starship.id))
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
