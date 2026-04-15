package viaduct.tenant.tutorial03

import viaduct.api.testing.TestSchema
import viaduct.api.testing.featureapp.KotlinFeatureAppTestContractBase

/**
 * Contract test for [SimpleResolversFeatureAppTest].
 *
 * This class exists solely to hold the `@TestSchema` annotation for codegen.
 * The actual tests and resolvers remain in the subclass for readability.
 */
@TestSchema(
    """

interface Person {
  firstname: String!
  lastname: String!
}

type User implements Node & Person @resolver {
  id: ID!
  firstname: String!
  lastname: String!
  fullName: String! @resolver
}

extend type Query {
  user(id: String!): User! @resolver
  person: Person! @resolver
  userWithArgs(firstname: String, lastname: String): User! @resolver
}
"""
)
abstract class SimpleResolversContractTest : KotlinFeatureAppTestContractBase()
