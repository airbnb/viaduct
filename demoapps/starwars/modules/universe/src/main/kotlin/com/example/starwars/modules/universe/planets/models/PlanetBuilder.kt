package com.example.starwars.modules.universe.planets.models

import viaduct.api.context.ExecutionContext
import viaduct.api.context.globalIDFor

/**
 * Builder class for mapping viaduct generated Planet objects from Planet entity data.
 */
class PlanetBuilder(private val ctx: ExecutionContext) {
    fun build(planet: Planet): viaduct.api.grts.Planet =
        viaduct.api.grts.Planet.Builder(ctx)
            .id(ctx.globalIDFor<viaduct.api.grts.Planet>(planet.id))
            .name(planet.name)
            .diameter(planet.diameter)
            .rotationPeriod(planet.rotationPeriod)
            .orbitalPeriod(planet.orbitalPeriod)
            .gravity(planet.gravity?.toDouble())
            .population(planet.population?.toDouble())
            .surfaceWater(planet.surfaceWater?.toDouble())
            .created(planet.created.toString())
            .edited(planet.edited.toString())
            .build()
}
