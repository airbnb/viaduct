package viaduct.tenant.tutorial07

import viaduct.api.testing.TestSchema
import viaduct.api.testing.featureapp.KotlinFeatureAppTestContractBase

@TestSchema(
    """
    extend type Query {
      users: [User!]! @resolver
      user(id: String!): User @resolver
    }

    type User {
      id: String!
      name: String!
      department: String @resolver(isBatching: true)
    }
"""
)
abstract class SimpleBatchResolverContractTest : KotlinFeatureAppTestContractBase()
