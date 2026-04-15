package viaduct.tenant.runtime.execution.variables.bootstrap.oneofviolation

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.api.testing.TestSchema
import viaduct.api.testing.featureapp.KotlinFeatureAppTestContractBase

@TestSchema(
    """
    input OneofInput @oneOf {
      stringValue: String
      intValue: Int
    }
    extend type Query {
      fromArgumentField(arg: OneofInput!): String @resolver
      intermediary(arg: OneofInput!): String @resolver
      fromVariablesProvider: String @resolver
    }
"""
)
abstract class TempOneOfViolationContractTest : KotlinFeatureAppTestContractBase() {
    @Test
    fun `oneof violation fails at runtime`() {
        val result = execute("query { fromVariablesProvider }")

        // If we get here, check for errors in the result
        assertTrue(result.errors?.isNotEmpty() == true, "Expected errors but got: ${result.errors}")

        val hasOneofError = result.errors?.any { error ->
            error.message?.contains("Exactly one key must be specified for OneOf type") == true ||
                error.message?.contains("OneOf") == true
        } == true

        assertTrue(hasOneofError, "Expected oneof violation error but got: ${result.errors?.map { it.message }}")
    }
}
