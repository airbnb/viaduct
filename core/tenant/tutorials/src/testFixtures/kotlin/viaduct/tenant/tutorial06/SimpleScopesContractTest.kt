package viaduct.tenant.tutorial06

import viaduct.api.testing.TestSchema
import viaduct.tenant.runtime.fixtures.FeatureAppTestBase

@TestSchema(
    """
    extend type Query @scope(to: ["USER"]) {
      myOrders(userId: String!): [String!]! @resolver
    }

    extend type Query @scope(to: ["ADMIN"]) {
      allUserData: [String!]! @resolver
    }
"""
)
abstract class SimpleScopesContractTest : FeatureAppTestBase()
