package com.example.starwars.modules.universe.starships.models;

import viaduct.java.api.context.ExecutionContext;
import viaduct.java.api.reflect.Type;

/** Maps starship entities to generated GraphQL runtime types. */
public final class StarshipBuilder {
  private final ExecutionContext context;

  public StarshipBuilder(ExecutionContext context) {
    this.context = context;
  }

  public viaduct.java.grts.Starship build(Starship starship) {
    return viaduct.java.grts.Starship.builder(context)
        .id(context.globalIDFor(Type.ofClass(viaduct.java.grts.Starship.class), starship.id()))
        .name(starship.name())
        .model(starship.model())
        .starshipClass(starship.starshipClass())
        .manufacturers(starship.manufacturers())
        .costInCredits(
            starship.costInCredits() == null ? null : starship.costInCredits().doubleValue())
        .length(starship.length() == null ? null : starship.length().doubleValue())
        .crew(starship.crew())
        .passengers(starship.passengers())
        .maxAtmospheringSpeed(starship.maxAtmospheringSpeed())
        .hyperdriveRating(
            starship.hyperdriveRating() == null ? null : starship.hyperdriveRating().doubleValue())
        .MGLT(starship.mglt())
        .cargoCapacity(
            starship.cargoCapacity() == null ? null : starship.cargoCapacity().doubleValue())
        .consumables(starship.consumables())
        .created(starship.created().toString())
        .edited(starship.edited().toString())
        .build();
  }
}
