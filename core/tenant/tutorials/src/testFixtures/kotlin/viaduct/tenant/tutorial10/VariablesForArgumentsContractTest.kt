package viaduct.tenant.tutorial10

import viaduct.api.testing.TestSchema
import viaduct.tenant.runtime.fixtures.FeatureAppTestBase

/**
 * Contract test for [VariablesForArgumentsFeatureAppTest].
 *
 * This class exists solely to hold the `@TestSchema` annotation for codegen.
 * The actual tests and resolvers remain in the subclass for readability.
 */
@TestSchema(
    """
extend type Query {
  getPosts(userId: String!, status: String!): String @resolver
  userPosts(userId: String!): String @resolver
  latestPosts: String @resolver
  dashboardPosts(userType: String!): String @resolver
}
"""
)
abstract class VariablesForArgumentsContractTest : FeatureAppTestBase()
