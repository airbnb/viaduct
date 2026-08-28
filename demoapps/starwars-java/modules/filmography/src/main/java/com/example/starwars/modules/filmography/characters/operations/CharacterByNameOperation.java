package com.example.starwars.modules.filmography.characters.operations;

import viaduct.java.api.annotations.GraphQLOperation;
import viaduct.java.api.documents.QueryFromAnnotation;

/** Statically validated subquery used by a Java resolver. */
@GraphQLOperation(
    """
    query($name: String!) {
      searchCharacter(search: { byName: $name }) {
        ...CharacterIdentityFields
      }
    }
    """)
public final class CharacterByNameOperation extends QueryFromAnnotation {
  public static final CharacterByNameOperation INSTANCE = new CharacterByNameOperation();

  private CharacterByNameOperation() {}
}
