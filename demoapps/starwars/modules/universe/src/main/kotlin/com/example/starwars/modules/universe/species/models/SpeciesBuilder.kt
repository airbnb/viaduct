package com.example.starwars.modules.universe.species.models

import viaduct.api.context.ExecutionContext
import viaduct.api.context.globalIDFor

/**
 * A builder class for constructing [viaduct.api.grts.Species] GraphQL objects from
 * [Species] entities.
 *
 * @property ctx The execution context used for building the GraphQL object.
 */
class SpeciesBuilder(private val ctx: ExecutionContext) {
    fun build(species: Species): viaduct.api.grts.Species =
        viaduct.api.grts.Species.Builder(ctx)
            .id(ctx.globalIDFor<viaduct.api.grts.Species>(species.id))
            .name(species.name)
            .classification(species.classification)
            .designation(species.designation)
            .averageHeight(species.averageHeight?.toDouble())
            .averageLifespan(species.averageLifespan)
            .eyeColors(species.eyeColors)
            .hairColors(species.hairColors)
            .language(species.language)
            .created(species.created.toString())
            .edited(species.edited.toString())
            .build()
}
