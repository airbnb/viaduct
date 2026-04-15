package viaduct.tenant.tutorial06

import viaduct.api.testing.TestSchema
import viaduct.api.testing.featureapp.KotlinFeatureAppTestContractBase

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
abstract class SimpleScopesContractTest : KotlinFeatureAppTestContractBase()
