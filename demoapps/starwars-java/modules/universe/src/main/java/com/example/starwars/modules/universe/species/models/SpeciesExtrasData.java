package com.example.starwars.modules.universe.species.models;

import java.util.List;

/** Additional species data exposed by the extras scope. */
public record SpeciesExtrasData(
    String culturalNotes,
    String rarityLevel,
    List<String> specialAbilities,
    String technologicalLevel) {
  public SpeciesExtrasData() {
    this(null, null, List.of(), null);
  }
}
