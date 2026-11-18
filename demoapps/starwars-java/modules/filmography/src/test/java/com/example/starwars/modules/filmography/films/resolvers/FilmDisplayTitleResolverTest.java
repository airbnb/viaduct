package com.example.starwars.modules.filmography.films.resolvers;

import static org.assertj.core.api.Assertions.assertThat;

import graphql.Scalars;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import org.junit.jupiter.api.Test;
import viaduct.engine.api.EngineSchema;
import viaduct.java.api.testing.ResolverTestBase;
import viaduct.java.api.types.Arguments;
import viaduct.java.grts.Film;
import viaduct.java.grts.Query;

final class FilmDisplayTitleResolverTest {
  private static final GraphQLObjectType FILM =
      GraphQLObjectType.newObject()
          .name("Film")
          .field(
              GraphQLFieldDefinition.newFieldDefinition().name("title").type(Scalars.GraphQLString))
          .field(
              GraphQLFieldDefinition.newFieldDefinition()
                  .name("displayTitle")
                  .type(Scalars.GraphQLString))
          .build();
  private static final EngineSchema SCHEMA =
      new EngineSchema(
          GraphQLSchema.newSchema()
              .query(
                  GraphQLObjectType.newObject()
                      .name("Query")
                      .field(GraphQLFieldDefinition.newFieldDefinition().name("film").type(FILM)))
              .build());
  private final ResolverTestBase test = new ResolverTestBase(SCHEMA);

  @Test
  void returnsTheTitle() {
    var inputs =
        new ResolverTestBase.FieldInputs<Film, Query, Arguments.NoArguments>()
            .objectValue(Film.builder(test.context()).title("A New Hope").build());

    assertThat(test.runFieldResolver(new FilmDisplayTitleResolver(), inputs).join())
        .isEqualTo("A New Hope");
  }
}
