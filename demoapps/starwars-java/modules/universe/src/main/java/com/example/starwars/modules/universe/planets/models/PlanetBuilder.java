package com.example.starwars.modules.universe.planets.models;

import viaduct.java.api.context.ExecutionContext;
import viaduct.java.api.reflect.Type;

/** Maps planet entities to generated GraphQL runtime types. */
public final class PlanetBuilder {
  private final ExecutionContext context;

  public PlanetBuilder(ExecutionContext context) {
    this.context = context;
  }

  public viaduct.java.grts.Planet build(Planet planet) {
    return viaduct.java.grts.Planet.builder(context)
        .id(context.globalIDFor(Type.ofClass(viaduct.java.grts.Planet.class), planet.id()))
        .name(planet.name())
        .diameter(planet.diameter())
        .rotationPeriod(planet.rotationPeriod())
        .orbitalPeriod(planet.orbitalPeriod())
        .gravity(planet.gravity() == null ? null : planet.gravity().doubleValue())
        .population(planet.population() == null ? null : planet.population().doubleValue())
        .surfaceWater(planet.surfaceWater() == null ? null : planet.surfaceWater().doubleValue())
        .created(planet.created().toString())
        .edited(planet.edited().toString())
        .build();
  }
}
