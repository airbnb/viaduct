package com.example.starwars.modules.universe.species.models;

import viaduct.java.api.context.ExecutionContext;
import viaduct.java.api.reflect.Type;

/** Maps species entities to generated GraphQL runtime types. */
public final class SpeciesBuilder {
  private final ExecutionContext context;

  public SpeciesBuilder(ExecutionContext context) {
    this.context = context;
  }

  public viaduct.java.grts.Species build(Species species) {
    return viaduct.java.grts.Species.builder(context)
        .id(context.globalIDFor(Type.ofClass(viaduct.java.grts.Species.class), species.id()))
        .name(species.name())
        .classification(species.classification())
        .designation(species.designation())
        .averageHeight(
            species.averageHeight() == null ? null : species.averageHeight().doubleValue())
        .averageLifespan(species.averageLifespan())
        .eyeColors(species.eyeColors())
        .hairColors(species.hairColors())
        .language(species.language())
        .created(species.created().toString())
        .edited(species.edited().toString())
        .build();
  }
}
