package viaduct.demoapp.starwars.builders

import viaduct.api.context.ExecutionContext
import viaduct.api.grts.Species
import viaduct.demoapp.starwars.data.StarWarsData

class SpeciesBuilder(private val ctx: ExecutionContext) {
    fun build(species: StarWarsData.Species): Species =
        Species.Builder(ctx)
            .id(ctx.globalIDFor(Species.Reflection, species.id))
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
