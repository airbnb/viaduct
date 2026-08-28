package com.example.starwars.modules.filmography.characters.models;

import viaduct.java.api.context.ExecutionContext;
import viaduct.java.api.reflect.Type;

/** Maps character entities to generated GraphQL runtime types. */
public final class CharacterBuilder {
  private final ExecutionContext context;

  public CharacterBuilder(ExecutionContext context) {
    this.context = context;
  }

  public viaduct.java.grts.Character build(Character character) {
    return viaduct.java.grts.Character.builder(context)
        .id(context.globalIDFor(Type.ofClass(viaduct.java.grts.Character.class), character.id()))
        .name(character.name())
        .birthYear(character.birthYear())
        .eyeColor(character.eyeColor())
        .gender(character.gender())
        .hairColor(character.hairColor())
        .height(character.height())
        .mass(character.mass() == null ? null : character.mass().doubleValue())
        .created(character.created().toString())
        .edited(character.edited().toString())
        .build();
  }
}
