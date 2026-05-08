package com.example.starwars.modules.universe.planets.models

import viaduct.api.context.ExecutionContext
import viaduct.api.context.globalIDFor
import viaduct.api.grts.Planet as GRTPlanet

/**
 * Builder class for mapping viaduct generated Planet objects from Planet entity data.
 */
class PlanetBuilder(private val ctx: ExecutionContext) {
    fun build(planet: Planet): GRTPlanet =
        GRTPlanet.of(ctx) {
            id(ctx.globalIDFor<GRTPlanet>(planet.id))
            name(planet.name)
            diameter(planet.diameter)
            rotationPeriod(planet.rotationPeriod)
            orbitalPeriod(planet.orbitalPeriod)
            gravity(planet.gravity?.toDouble())
            population(planet.population?.toDouble())
            surfaceWater(planet.surfaceWater?.toDouble())
            created(planet.created.toString())
            edited(planet.edited.toString())
        }
}
