package viaduct.tenant.runtime.execution.includedirective

import org.junit.jupiter.api.Test
import viaduct.graphql.test.assertEquals
import viaduct.tenant.runtime.fixtures.FeatureAppTestBase
import viaduct.tenant.runtime.fixtures.TestSchema

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
@TestSchema(
    """
    extend type Query {
     "Return a Foo object"
     foo: Foo @resolver
     "Return a Thrower object (only tested with @include(if:false), so resolver may throw)"
     thrower: Thrower @resolver
     "Return false"
     booleanValue: Boolean @resolver
    }

    type Thrower {
     "Throw an error (this resolver should never be called when @include works correctly)"
     willThrow: Int @resolver
    }

    type Foo {
      "Return 10"
      intValue: Int @resolver
      "Return \"result value\""
      sValue: String @resolver
    }
"""
)
abstract class IncludeDirectiveContractTest : FeatureAppTestBase() {
    @Test
    fun `using include directive as false`() {
        execute(
            query = """
                query {
                    foo @include(if:false) {
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
                    foo @include(if:true) {
                      intValue
                      sValue
                    }
                 }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                "foo" to {
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
                    foo @include(if:true) {
                      intValue
                      sValue
                    }
                    thrower @include(if:false) {
                        willThrow
                    }
                 }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                "foo" to {
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
                    foo @include(if: ${'$'}includeFoo) {
                      intValue
                      sValue
                    }
                    thrower @include(if:false) {
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
