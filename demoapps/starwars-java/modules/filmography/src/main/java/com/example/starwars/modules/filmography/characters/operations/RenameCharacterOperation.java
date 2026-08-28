package com.example.starwars.modules.filmography.characters.operations;

import viaduct.java.api.annotations.GraphQLOperation;
import viaduct.java.api.documents.MutationFromAnnotation;

/** Statically validated submutation used by a Java resolver. */
@GraphQLOperation(
    """
    mutation($id: ID!, $name: String!) {
      updateCharacterName(id: $id, name: $name) {
        ...CharacterIdentityFields
      }
    }
    """)
public final class RenameCharacterOperation extends MutationFromAnnotation {
  public static final RenameCharacterOperation INSTANCE = new RenameCharacterOperation();

  private RenameCharacterOperation() {}
}
