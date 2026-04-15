package viaduct.tenant.tutorial04

import viaduct.api.testing.TestSchema
import viaduct.api.testing.featureapp.KotlinFeatureAppTestContractBase

/**
 * Contract test for [SimpleBackingDataFeatureAppTest].
 *
 * This class exists solely to hold the `@TestSchema` annotation for codegen.
 * The actual tests and resolvers remain in the subclass for readability.
 */
@TestSchema(
    """
extend type Query {
  user(id: String!): User! @resolver
}

type User implements Node @resolver {
  id: ID!
  name: String!
  email: String!
  averageStars: Float! @resolver      # Computed from backing data
  reviewsCount: Int! @resolver        # Computed from backing data
  reviewsData: BackingData            # The expensive operation
    @resolver
    @backingData(class: "UserReviewsData")  # Your custom Kotlin class
}
"""
)
abstract class SimpleBackingDataContractTest : KotlinFeatureAppTestContractBase()
