package viaduct.tenant.tutorial13

import viaduct.api.testing.TestSchema
import viaduct.api.testing.featureapp.KotlinFeatureAppTestContractBase

@TestSchema(
    """
    type Product {
      name: String
      price: Int
    }

    type ProductFactory @namespaceType {
      create: Product @resolver
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
