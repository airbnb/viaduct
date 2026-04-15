package viaduct.tenant.runtime.execution.policycheck

import viaduct.api.testing.TestSchema
import viaduct.api.testing.featureapp.KotlinFeatureAppTestContractBase

@TestSchema(
    """
    directive @policyCheck(canAccess: Boolean) on FIELD_DEFINITION | OBJECT

    extend type Query @scope(to: ["SCOPE1"]) {
      canAccessField: String @resolver @policyCheck(canAccess: true)
      canNotAccessField: String @resolver @policyCheck(canAccess: false)
      canNotAccessType: CanNotAccessPerson @resolver @policyCheck(canAccess: false)
    }

    type CanAccessPerson implements Node @resolver @scope(to: ["SCOPE1"]) @policyCheck(canAccess: true) {
      id: ID!
      name: String!
      ssn: String!
    }

    type CanNotAccessPerson implements Node @resolver @scope(to: ["SCOPE1"]) @policyCheck(canAccess: false) {
      id: ID!
      name: String!
      ssn: String!
    }
"""
)
abstract class PolicyCheckContractTest : KotlinFeatureAppTestContractBase()
