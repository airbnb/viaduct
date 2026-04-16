package viaduct.tenant.runtime.execution.batchresolver.bootstrap

import viaduct.api.testing.TestSchema
import viaduct.api.testing.featureapp.KotlinFeatureAppTestContractBase

@TestSchema(
    """
    extend type Query {
      field: String @resolver(isSelective: true)
      batchField: String @resolver(isBatching: true)
    }

    type TestNode implements Node @resolver {
      id: ID!
      value: String
    }

    type TestBatchNode implements Node @resolver(isBatching: true) {
      id: ID!
      value: String
    }
"""
)
abstract class TenantAPIBootstrapperContractTest : KotlinFeatureAppTestContractBase()
