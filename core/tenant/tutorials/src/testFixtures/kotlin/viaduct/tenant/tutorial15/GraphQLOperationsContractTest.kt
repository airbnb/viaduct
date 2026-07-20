package viaduct.tenant.tutorial15

import viaduct.api.testing.TestSchema
import viaduct.api.testing.featureapp.KotlinFeatureAppTestContractBase

/**
 * Contract test for [GraphQLOperationsFeatureAppTest].
 *
 * This class exists solely to hold the `@TestSchema` annotation for codegen.
 * The actual tests, operations, fragments, and resolvers live in the subclass for readability.
 */
@TestSchema(
    """
extend type Query {
  # Returns "Hello, World!" — the target of a no-variable operation
  greeting: String! @resolver
  # Returns "echo:<value>" — the target of an operation that passes a variable
  echo(value: String!): String! @resolver
  # Returns a User with id=<id>, name="User-<id>" — target of an operation that spreads a fragment
  user(id: ID!): User @resolver
  # A container whose fields run the query operations above via ctx.query(operation)
  runner: Runner @resolver
}

type User {
  id: ID!
  name: String!
}

type Runner {
  # Runs a @GraphQLOperation with NO variables; returns greeting.
  fetchGreeting: String! @resolver
  # Runs a @GraphQLOperation WITH a variable; returns echo(value).
  fetchEcho(value: String!): String! @resolver
  # Runs a @GraphQLOperation that spreads a named fragment; returns "<name> (<id>)".
  fetchUserCard(id: ID!): String! @resolver
}

extend type Mutation {
  # Returns "saved:<value>" — target of a mutation operation
  save(value: String!): String! @resolver
  # Runs a @GraphQLOperation mutation via ctx.mutation(operation); returns its result.
  runSave(value: String!): String! @resolver
}
"""
)
abstract class GraphQLOperationsContractTest : KotlinFeatureAppTestContractBase()
