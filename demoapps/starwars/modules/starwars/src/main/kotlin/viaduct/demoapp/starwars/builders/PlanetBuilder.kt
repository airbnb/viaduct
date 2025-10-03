package viaduct.demoapp.starwars.builders

import viaduct.api.context.ExecutionContext
import viaduct.api.grts.Planet
import viaduct.demoapp.starwars.data.StarWarsData

class PlanetBuilder(private val ctx: ExecutionContext) {
    fun build(planet: StarWarsData.Planet): Planet =
        Planet.Builder(ctx)
            .id(ctx.globalIDFor(Planet.Reflection, planet.id))
            .name(planet.name)
            .diameter(planet.diameter)
            .rotationPeriod(planet.rotationPeriod)
            .orbitalPeriod(planet.orbitalPeriod)
            .gravity(planet.gravity?.toDouble())
            .population(planet.population?.toDouble())
            .climates(planet.climates)
            .terrains(planet.terrains)
            .surfaceWater(planet.surfaceWater?.toDouble())
            .created(planet.created.toString())
            .edited(planet.edited.toString())
            .build()
}
