package viaduct.tenant.runtime.fixtures.variablesprovidercontract

import org.junit.jupiter.api.Test
import viaduct.graphql.test.assertEquals
import viaduct.tenant.runtime.fixtures.FeatureAppTestBase

/**
 * Contract test for the VariablesProvider API.
 *
 * Defines the SDL and assertions for:
 * - Variables passed via the @Resolver variables parameter (from field argument)
 * - Variables provided via VariablesProvider class (scalar Int)
 * - Variables provided via VariablesProvider with an Input type
 * - Variables provided via VariablesProvider with a GlobalID type
 * - Variables provided via VariablesProvider with a nested complex Input type
 *
 * Note: This contract test is Kotlin-only because VariablesProvider and @Variables
 * are not available in the Java Tenant API.
 *
 * Extend this class and provide resolver implementations to verify that a given
 * runtime correctly supports these patterns.
 */
abstract class VariablesProviderContractTest : FeatureAppTestBase() {
    init {
        sdl = """
            | #START_SCHEMA
            | extend type Query {
            |   "Use @Resolver variables to pass arg from field argument to intermediary; return arg value (e.g. 7)"
            |   fromArgumentField(arg: Int!): Int @resolver
            |   "Return arg value (pass-through resolver called by variables-based resolvers)"
            |   intermediary(arg: Int!): Int @resolver
            |   "Return input.x (pass-through resolver for Input type)"
            |   intermediaryTakesInput(input: MyInput!): Int @resolver
            |   "Return the input global ID string (pass-through resolver)"
            |   intermediaryTakesGlobalID(input: ID!): String @resolver
            |   "Return \"Color: <color>, Values: <intArray joined by comma>\" from the nested input"
            |   intermediaryTakesNestedComplexInput(input: InputWithNestedInput!): String @resolver
            |   "Use VariablesProvider to supply arg=123 to intermediary; return 123"
            |   fromVariablesProvider: Int @resolver
            |   "Use VariablesProvider to supply MyInput(x=456) to intermediaryTakesInput; return 456"
            |   fromVariablesProviderWithInput: Int @resolver
            |   "Use VariablesProvider to supply GlobalID(\"MyType\",\"123\") to intermediaryTakesGlobalID; return \"TXlUeXBlOjEyMw==\""
            |   fromVariablesProviderWithGlobalID: String @resolver
            |   "Use VariablesProvider to supply InputWithNestedInput(ComplexInput(RED,[1,2,3])); return \"Color: RED, Values: 1,2,3\""
            |   fromVariablesProviderWithNestedComplexInput: String @resolver
            | }
            | type MyType implements Node { id: ID!, x: Int! } # Just used to have a valid type for a global ID
            | input MyInput { x: Int! }
            | input MyInputWithGlobalID { globalId: ID! }
            | enum Color { RED, GREEN, BLUE }
            | input ComplexInput { color: Color!, intArray: [Int!]! }
            | input InputWithNestedInput { complexInput: ComplexInput! }
            | #END_SCHEMA
        """.trimMargin()
    }

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
