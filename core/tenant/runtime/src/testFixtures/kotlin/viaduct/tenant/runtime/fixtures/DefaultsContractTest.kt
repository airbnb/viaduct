package viaduct.tenant.runtime.fixtures

import org.junit.jupiter.api.Test
import viaduct.graphql.test.assertEquals

/**
 * Contract test for resolvers that exercise inputs with default values.
 *
 * Defines the SDL and assertions for:
 * - Required selections with empty input objects that have defaults filled in
 * - Required selections with null input that bypasses default filling
 * - Fields with arguments that have inner default values
 * - Passing an empty input object as a variable with defaults filled in
 *
 * Extend this class and provide resolver implementations to verify that a given
 * runtime correctly supports these patterns.
 */
abstract class DefaultsContractTest : FeatureAppTestBase() {
    init {
        sdl = """
            | #START_SCHEMA
            | extend type Query {
            |   "Call inner(inp: InputWithDefaults(x=1)) — i.e. pass an explicit object with x=1; return 6"
            |   outer1: Int! @resolver
            |   "Call inner(inp: null) — i.e. pass null; return -5"
            |   outer2: Int! @resolver
            |   "Call inner(inp: arg) — delegates the arg; return 7"
            |   outer3(arg: InputWithDefaults! = {}): Int! @resolver
            |   "Pass arg as a variable to inner via @Resolver variables; return 22"
            |   outer4(arg: InputWithDefaults! = {}): Int! @resolver
            |
            |   "Return inp.x + 5 when inp is non-null; return -5 when inp is null"
            |   inner(inp: InputWithDefaults): Int! @resolver
            | }
            |
            | input InputWithDefaults {
            |   x:Int! = 1
            | }
            | #END_SCHEMA
        """.trimMargin()
    }

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
