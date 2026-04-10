package viaduct.tenant.tutorial09

import viaduct.api.testing.TestSchema
import viaduct.tenant.runtime.fixtures.FeatureAppTestBase

/**
 * Contract test for [VariablesDirectivesFeatureAppTest].
 *
 * This class exists solely to hold the `@TestSchema` annotation for codegen.
 * The actual tests and resolvers remain in the subclass for readability.
 */
@TestSchema(
    """
    type User implements Node @resolver {
      id: ID!
      name: String!
      anonymousReviews: [String!]! @resolver
      verifiedReviews: [String!]! @resolver
      reviews(anonymous: Boolean!): [String!]! @resolver
      computedReviews: [String!]! @resolver
      computedReviewsWithArgs(userType: String!): [String!]! @resolver
    }

    extend type Query {
      user(id: String!): User! @resolver
    }
"""
)
abstract class VariablesDirectivesContractTest : FeatureAppTestBase()
