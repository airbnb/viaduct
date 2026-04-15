package viaduct.tenant.runtime.execution.variables.trivial

import org.junit.jupiter.api.Test
import viaduct.api.testing.TestSchema
import viaduct.api.testing.featureapp.KotlinFeatureAppTestContractBase
import viaduct.graphql.test.assertEquals

/**
 * Contract test for VariablesProvider patterns.
 *
 * Defines the SDL and assertions for:
 * - Variables via variables parameter
 * - Variables via VariablesProvider
 * - Variable with Input type via VariablesProvider
 * - Variable with Global ID type via VariablesProvider
 * - Variable with complex data elements in a nested input via VariablesProvider
 *
 * Extend this class and provide resolver implementations to verify that a given
 * runtime correctly supports these patterns.
 */
@TestSchema(
    """
    extend type Query {
      fromArgumentField(arg: Int!): Int @resolver
      intermediary(arg: Int!): Int @resolver
      intermediaryTakesInput(input: MyInput!): Int @resolver
      intermediaryTakesGlobalID(input: ID!): String @resolver
      intermediaryTakesNestedComplexInput(input: InputWithNestedInput!): String @resolver
      fromVariablesProvider: Int @resolver
      fromVariablesProviderWithInput: Int @resolver
      fromVariablesProviderWithGlobalID: String @resolver
      fromVariablesProviderWithNestedComplexInput: String @resolver
    }
    type MyType implements Node { id: ID!, x: Int! }
    input MyInput { x: Int! }
    input MyInputWithGlobalID { globalId: ID! }
    enum Color { RED, GREEN, BLUE }
    input ComplexInput { color: Color!, intArray: [Int!]! }
    input InputWithNestedInput { complexInput: ComplexInput! }
"""
)
abstract class VariablesProviderContractTest : KotlinFeatureAppTestContractBase() {
    @Test
    fun `variables via variables parameter`() {
        execute(
            query = """
                query {
                    fromArgumentField(arg: 7)
                }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                "fromArgumentField" to 7
            }
        }
    }

    @Test
    fun `variables via VariablesProvider`() {
        execute("{ fromVariablesProvider }").assertEquals {
            "data" to { "fromVariablesProvider" to 123 }
        }
    }

    @Test
    fun `variable with Input type via VariablesProvider`() {
        execute("{ fromVariablesProviderWithInput }").assertEquals {
            "data" to { "fromVariablesProviderWithInput" to 456 }
        }
    }

    @Test
    fun `variable with Global ID type via VariablesProvider`() {
        execute("{ fromVariablesProviderWithGlobalID }").assertEquals {
            "data" to { "fromVariablesProviderWithGlobalID" to "TXlUeXBlOjEyMw==" }
        }
    }

    @Test
    fun `variable with complex data elements in a nested input via VariablesProvider`() {
        execute("{ fromVariablesProviderWithNestedComplexInput }").assertEquals {
            "data" to { "fromVariablesProviderWithNestedComplexInput" to "Color: RED, Values: 1,2,3" }
        }
    }
}
