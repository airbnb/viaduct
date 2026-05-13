package com.example.starwars.modules.universe.starships.models

import viaduct.api.context.ExecutionContext
import viaduct.api.context.globalIDFor
import viaduct.api.grts.Starship as GRTStarship

/**
 * A builder class for constructing [GRTStarship] GraphQL objects from
 * [Starship] entities.
 *
 * @property ctx The execution context used for building the GraphQL object.
 */
class StarshipBuilder(private val ctx: ExecutionContext) {
    fun build(starship: Starship): GRTStarship =
        // tag::global_id_example[18] Example using global IDs
        GRTStarship.of(ctx) {
            id(ctx.globalIDFor<GRTStarship>(starship.id))
            name(starship.name)
            model(starship.model)
            starshipClass(starship.starshipClass)
            manufacturers(starship.manufacturers)
            costInCredits(starship.costInCredits?.toDouble())
            length(starship.length?.toDouble())
            crew(starship.crew)
            passengers(starship.passengers)
            maxAtmospheringSpeed(starship.maxAtmospheringSpeed)
            hyperdriveRating(starship.hyperdriveRating?.toDouble())
            MGLT(starship.mglt)
            cargoCapacity(starship.cargoCapacity?.toDouble())
            consumables(starship.consumables)
            created(starship.created.toString())
            edited(starship.edited.toString())
        }
}
