package viaduct.tenant.tutorial02

import viaduct.api.testing.TestSchema
import viaduct.tenant.runtime.fixtures.FeatureAppTestBase

/**
 * Contract test for [SimpleNodeResolverFeatureAppTest].
 *
 * This class exists solely to hold the `@TestSchema` annotation for codegen.
 * The actual tests and resolvers remain in the subclass for readability.
 */
@TestSchema(
    """
    type Foo implements Node @resolver {  # <- Node interface + @resolver = generate NodeResolvers.Foo()
      id: ID!        # Required by Node interface
      bar: String!   # Your custom field
    }

    extend type Query {
      foo(id: String!): Foo! @resolver  # Query that returns a Node
    }
"""
)
abstract class SimpleNodeResolverContractTest : FeatureAppTestBase()
