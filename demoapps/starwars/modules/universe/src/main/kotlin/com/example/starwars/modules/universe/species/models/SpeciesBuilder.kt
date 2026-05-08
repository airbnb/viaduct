package com.example.starwars.modules.universe.species.models

import viaduct.api.context.ExecutionContext
import viaduct.api.context.globalIDFor
import viaduct.api.grts.Species as GRTSpecies

/**
 * A builder class for constructing [GRTSpecies] GraphQL objects from
 * [Species] entities.
 *
 * @property ctx The execution context used for building the GraphQL object.
 */
class SpeciesBuilder(private val ctx: ExecutionContext) {
    fun build(species: Species): GRTSpecies =
        GRTSpecies.of(ctx) {
            id(ctx.globalIDFor<GRTSpecies>(species.id))
            name(species.name)
            classification(species.classification)
            designation(species.designation)
            averageHeight(species.averageHeight?.toDouble())
            averageLifespan(species.averageLifespan)
            eyeColors(species.eyeColors)
            hairColors(species.hairColors)
            language(species.language)
            created(species.created.toString())
            edited(species.edited.toString())
        }
}
