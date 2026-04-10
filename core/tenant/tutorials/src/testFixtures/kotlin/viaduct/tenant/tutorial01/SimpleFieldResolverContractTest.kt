package viaduct.tenant.tutorial01

import viaduct.api.testing.TestSchema
import viaduct.tenant.runtime.fixtures.FeatureAppTestBase

/**
 * Contract test for [SimpleFieldResolverFeatureAppTest].
 *
 * This class exists solely to hold the `@TestSchema` annotation for codegen.
 * The actual tests and resolvers remain in the subclass for readability.
 */
@TestSchema(
    """
    extend type Query {
      foo: String! @resolver
    }
"""
)
abstract class SimpleFieldResolverContractTest : FeatureAppTestBase()
