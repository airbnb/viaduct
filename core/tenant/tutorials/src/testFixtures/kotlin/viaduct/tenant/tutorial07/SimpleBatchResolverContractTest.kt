package viaduct.tenant.tutorial07

import viaduct.api.testing.TestSchema
import viaduct.tenant.runtime.fixtures.FeatureAppTestBase

@TestSchema(
    """
    extend type Query {
      users: [User!]! @resolver
      user(id: String!): User @resolver
    }

    type User {
      id: String!
      name: String!
      department: String @resolver
    }
"""
)
abstract class SimpleBatchResolverContractTest : FeatureAppTestBase()
