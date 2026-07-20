package viaduct.tenant.tutorial14

import viaduct.api.testing.TestSchema
import viaduct.api.testing.featureapp.KotlinFeatureAppTestContractBase

/**
 * Contract test for [NamedFragmentsFeatureAppTest].
 *
 * This class exists solely to hold the `@TestSchema` annotation for codegen.
 * The actual tests, named fragments, and resolvers live in the subclass for readability.
 */
@TestSchema(
    """
extend type Query {
  # Returns a User with id=<id>, name="User-<id>", email="<id>@example.com"
  user(id: ID!): User @resolver
}

type User {
  id: ID!
  name: String!
  email: String!
  # Uses the named fragment UserIdentity via objectValueFragment; returns "<name> (<id>)"
  card: String! @resolver
  # Uses the nested named fragment UserProfile (which spreads UserIdentity);
  # returns "<name> <<email>> [<id>]"
  profile: String! @resolver
}
"""
)
abstract class NamedFragmentsContractTest : KotlinFeatureAppTestContractBase()
