package viaduct.tenant.tutorial05

import viaduct.api.testing.TestSchema
import viaduct.api.testing.featureapp.KotlinFeatureAppTestContractBase

@TestSchema(
    """
    type User implements Node @resolver {
      id: ID!
      name: String
      email: String
    }

    input UserInput {
      name: String!
      email: String!
    }

    extend type Query {
      user(id: String!): User @resolver
    }

    extend type Mutation {
      createUser(input: UserInput!): User @resolver
      updateUser(id: String!, input: UserInput!): User @resolver
    }
"""
)
abstract class SimpleMutationsContractTest : KotlinFeatureAppTestContractBase()
