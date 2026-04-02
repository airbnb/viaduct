package viaduct.tenant.runtime.fixtures

import org.junit.jupiter.api.Test
import viaduct.graphql.test.assertEquals

/**
 * Contract test for the @include directive.
 *
 * Defines the SDL and assertions for:
 * - @include(if: false) skips field execution entirely
 * - @include(if: true) executes the field normally
 * - @include(if: false) does not call @resolver even if it would throw
 * - @include(if: $variable) with a variable that evaluates to false
 *
 * Extend this class and provide resolver implementations to verify that a given
 * runtime correctly supports these patterns.
 */
abstract class IncludeDirectiveContractTest : FeatureAppTestBase() {
    init {
        sdl = """
            | #START_SCHEMA
            | extend type Query {
            |  "Return a Foo object"
            |  fooResult: Foo @resolver
            |  "Return a Thrower object (only tested with @include(if:false), so resolver may throw)"
            |  throwingResult: Thrower @resolver
            |  "Return false"
            |  booleanValue: Boolean @resolver
            | }
            |
            | type Thrower {
            |  "Throw an error (this resolver should never be called when @include works correctly)"
            |  willThrow: Int @resolver
            | }
            |
            | type Foo {
            |   "Return 10"
            |   intValue: Int @resolver
            |   "Return \"result value\""
            |   sValue: String @resolver
            | }
            | #END_SCHEMA
        """.trimMargin()
    }

    @Test
    fun `using include directive as false`() {
        execute(
            query = """
                query {
                    fooResult @include(if:false) {
                      intValue
                      sValue
                    }
                 }
            """.trimIndent()
        ).assertEquals {
            "data" to {}
        }
    }

    @Test
    fun `using include directive as true`() {
        execute(
            query = """
                query {
                    fooResult @include(if:true) {
                      intValue
                      sValue
                    }
                 }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                "fooResult" to {
                    "intValue" to 10
                    "sValue" to "result value"
                }
            }
        }
    }

    @Test
    fun `using include directive will not call @resolver even if it throws`() {
        execute(
            query = """
                query {
                    fooResult @include(if:true) {
                      intValue
                      sValue
                    }
                    throwingResult @include(if:false) {
                        willThrow
                    }
                 }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                "fooResult" to {
                    "intValue" to 10
                    "sValue" to "result value"
                }
            }
        }
    }

    @Test
    fun `using include as a given parameter from another @resolver`() {
        execute(
            query = """
                query MyQuery(${'$'}includeFoo: Boolean!){
                    booleanValue
                    fooResult @include(if: ${'$'}includeFoo) {
                      intValue
                      sValue
                    }
                    throwingResult @include(if:false) {
                        willThrow
                    }
                 }
            """.trimIndent(),
            variables = mapOf(
                "includeFoo" to false
            )
        ).assertEquals {
            "data" to {
                "booleanValue" to false
            }
        }
    }
}
