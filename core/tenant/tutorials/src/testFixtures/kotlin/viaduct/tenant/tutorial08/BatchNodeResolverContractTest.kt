package viaduct.tenant.tutorial08

import viaduct.api.testing.TestSchema
import viaduct.api.testing.featureapp.KotlinFeatureAppTestContractBase

@TestSchema(
    """
    type Product implements Node @resolver(isBatching: true) {
      id: ID!
      name: String!
      price: Float!
      category: String!
    }

    extend type Query {
      products(ids: [String!]!): [Product!]! @resolver
      product(id: String!): Product! @resolver
    }
"""
)
abstract class BatchNodeResolverContractTest : KotlinFeatureAppTestContractBase()
