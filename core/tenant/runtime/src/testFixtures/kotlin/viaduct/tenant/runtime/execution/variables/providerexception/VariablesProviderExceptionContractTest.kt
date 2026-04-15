package viaduct.tenant.runtime.execution.variables.providerexception

import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import strikt.api.expectThat
import strikt.assertions.contains
import viaduct.api.testing.TestSchema
import viaduct.api.testing.featureapp.KotlinFeatureAppTestContractBase
import viaduct.graphql.test.assertEquals

/**
 * Contract test for VariablesProvider.provide throwing exceptions during query execution.
 * The exception should be caught and turned into a GraphQL field error,
 * while the rest of the query execution should be successful.
 */
@TestSchema(
    """
    extend type Query {
      fromArgumentField(arg: Int!): Int @resolver
      intermediary(arg: Int!): Int @resolver
      fromVariablesProvider: Int @resolver
      workingField: String @resolver
    }
"""
)
abstract class VariablesProviderExceptionContractTest : KotlinFeatureAppTestContractBase() {
    @Test
    fun `variables provider exception becomes field error while rest of query succeeds`() {
        // Test working fields succeed
        val workingResult = execute(
            query = """
                query {
                    workingField
                    fromArgumentField(arg: 42)
                }
            """.trimIndent()
        )

        workingResult.assertEquals {
            "data" to {
                "workingField" to "success"
                "fromArgumentField" to 42
            }
        }

        // Test that the field with variables provider exception throws an exception during execution
        val result = execute(
            query = """
                query {
                    fromVariablesProvider
                }
            """.trimIndent()
        )

        // expect that result.errors is not empty and contains the expected error message
        assertTrue(result.errors.isNotEmpty(), "Expected errors but found none")
        expectThat(result.errors[0].message)
            .describedAs("Expected error message to contain 'Variables provider failed!'")
            .contains("Variables provider failed!")
    }
}
