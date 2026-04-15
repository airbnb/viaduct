package viaduct.tenant.runtime.execution.filtertest

import viaduct.api.testing.TestSchema
import viaduct.api.testing.featureapp.KotlinFeatureAppTestContractBase

@TestSchema(
    """
    type TestScope1Object @scope(to: ["SCOPE1"]) {
        strValue: String!
    }
    type TestScope2Object @scope(to: ["SCOPE2"]) {
      strValue: String!
    }

    extend type Query @scope(to: ["SCOPE1"]) {
      scope1Value: TestScope1Object @resolver
    }

    extend type Query @scope(to: ["SCOPE2"]) {
      scope2Value: TestScope2Object @resolver
    }
"""
)
abstract class TenantPackageFilteringContractTest : KotlinFeatureAppTestContractBase()
