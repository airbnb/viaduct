package viaduct.tenant.runtime.execution.batchresolver.bootstrap

import viaduct.api.testing.TestSchema
import viaduct.tenant.runtime.fixtures.FeatureAppTestBase

@TestSchema(
    """
    extend type Query {
      field: String @resolver(isSelective: true)
      batchField: String @resolver
    }

    type TestNode implements Node @resolver {
      id: ID!
      value: String
    }

    type TestBatchNode implements Node @resolver {
      id: ID!
      value: String
    }
"""
)
abstract class TenantAPIBootstrapperContractTest : FeatureAppTestBase()
