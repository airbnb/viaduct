package viaduct.tenant.tutorial13

import viaduct.api.testing.TestSchema
import viaduct.api.testing.featureapp.KotlinFeatureAppTestContractBase

@TestSchema(
    """
    type Product {
      name: String
      price: Int
      metadata: JSON
      related: Product
    }

    enum ProductKind {
      PHYSICAL
      DIGITAL
    }

    input ProductSpecInput {
      quantity: Int!
    }

    type Owner implements Node {
      id: ID!
    }

    type ProductFactory @namespaceType {
      create: Product @resolver
      createWithArguments(
        name: String!
        metadata: JSON!
        spec: ProductSpecInput!
        kind: ProductKind!
        tags: [String!]!
        ownerId: ID! @idOf(type: "Owner")
      ): Product @resolver
    }

    type Factories @namespaceType {
      products: ProductFactory
    }

    extend type Query {
      _factories: Factories
      product: Product @resolver
    }
"""
)
abstract class RootFieldRefContractTest : KotlinFeatureAppTestContractBase()
