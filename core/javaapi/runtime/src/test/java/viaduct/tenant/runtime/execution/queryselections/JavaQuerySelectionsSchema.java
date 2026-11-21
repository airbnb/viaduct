package viaduct.tenant.runtime.execution.queryselections;

import viaduct.api.testing.TestSchema;

@TestSchema(JavaQuerySelectionsSchema.SCHEMA)
public final class JavaQuerySelectionsSchema {
  public static final String SCHEMA =
      """
      extend type Query {
        viewer: User @resolver
        nullableViewer: User @resolver
        user(id: ID!): User @resolver
      }
      extend type Mutation {
        updateUserWithViewerInfo(userId: ID!): UpdateResult! @resolver
      }
      type User {
        id: ID!
        name: String!
        displayName: String! @resolver
        displayNameFromNullViewer: String! @resolver
        greeting: String! @resolver
      }
      type UpdateResult {
        success: Boolean!
        message: String!
      }
      """;

  private JavaQuerySelectionsSchema() {}
}
