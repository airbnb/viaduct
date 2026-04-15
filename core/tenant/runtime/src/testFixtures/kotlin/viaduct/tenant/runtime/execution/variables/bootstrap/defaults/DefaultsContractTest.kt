package viaduct.tenant.runtime.execution.variables.bootstrap.defaults

import org.junit.jupiter.api.Test
import viaduct.api.testing.TestSchema
import viaduct.api.testing.featureapp.KotlinFeatureAppTestContractBase
import viaduct.graphql.test.assertEquals

@TestSchema(
    """
    extend type Query {
      outer1: Int! @resolver
      outer2: Int! @resolver
      outer3(arg: InputWithDefaults! = {}): Int! @resolver
      outer4(arg: InputWithDefaults! = {}): Int! @resolver

      inner(inp: InputWithDefaults): Int! @resolver
    }

    input InputWithDefaults {
      x:Int! = 1
    }
"""
)
abstract class DefaultsContractTest : KotlinFeatureAppTestContractBase() {
    @Test
    fun `a required selection can provide an empty input object that will have its defaults filled in`() {
        execute("{ outer1 }")
            .assertEquals {
                "data" to {
                    "outer1" to 6
                }
            }
    }

    @Test
    fun `a required selection can provide a null object that will not have its defaults filled in`() {
        execute("{ outer2 }")
            .assertEquals {
                "data" to {
                    "outer2" to -5
                }
            }
    }

    @Test
    fun `a field with an argument with an inner default value can be omitted and all defaults will be filled in`() {
        execute("{ outer3 }")
            .assertEquals {
                "data" to {
                    "outer3" to 7
                }
            }

        execute("{ outer3(arg:{}) }")
            .assertEquals {
                "data" to {
                    "outer3" to 7
                }
            }
    }

    @Test
    fun `a resolver can pass an empty input object as a variable and all defaults will be filled in`() {
        execute("{ outer4 }")
            .assertEquals {
                "data" to {
                    "outer4" to 22
                }
            }

        execute("{ outer4(arg:{}) }")
            .assertEquals {
                "data" to {
                    "outer4" to 22
                }
            }
    }
}
