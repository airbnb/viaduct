package viaduct.tenant.tutorial11

import viaduct.api.testing.TestSchema
import viaduct.api.testing.featureapp.KotlinFeatureAppTestContractBase

/**
 * Contract test for [SimpleSubqueriesFeatureAppTest].
 *
 * This class exists solely to hold the `@TestSchema` annotation for codegen.
 * The actual tests and resolvers remain in the subclass for readability.
 */
@TestSchema(
    """
extend type Query {
  greeting: String @resolver          # Returns a static greeting string
  multiply(n: Int!): Int @resolver    # Returns n * 2
  enriched: EnrichedGreeting @resolver
}

type EnrichedGreeting {
  # ctx.query() — fetches data from the Query root at runtime
  message: String @resolver
  # ctx.query(selections, variables) — passes a computed value as a subquery variable
  doubled(input: Int!): Int @resolver
}

extend type Mutation {
  step: Int @resolver              # Increments and returns counter
  pipeline: String @resolver       # Uses ctx.mutation() + ctx.query() together
}
"""
)
abstract class SimpleSubqueriesContractTest : KotlinFeatureAppTestContractBase()
