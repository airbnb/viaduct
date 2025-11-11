@file:Suppress("unused", "ClassName")

package viaduct.tenant.runtime.execution.variables.bootstrap.defaults

import org.junit.jupiter.api.Test
import viaduct.api.Resolver
import viaduct.api.Variable
import viaduct.graphql.test.assertEquals
import viaduct.tenant.runtime.execution.variables.bootstrap.defaults.resolverbases.QueryResolvers
import viaduct.tenant.runtime.fixtures.FeatureAppTestBase

/**
 * Tests for queries and resolvers that exercise inputs with default values.
 */
class DefaultsFeatureAppTest : FeatureAppTestBase() {
    override var sdl =
        """
        | #START_SCHEMA
        | extend type Query {
        |   outer1: Int! @resolver
        |   outer2: Int! @resolver
        |   outer3(arg: InputWithDefaults! = {}): Int! @resolver
        |   outer4(arg: InputWithDefaults! = {}): Int! @resolver
        |
        |   inner(inp: InputWithDefaults): Int! @resolver
        | }
        |
        | input InputWithDefaults {
        |   x:Int! = 1
        | }
        | #END_SCHEMA
        """.trimMargin()

    @Resolver("fragment _ on Query { inner(inp: {}) }")
    class Query_Outer1Resolver : QueryResolvers.Outer1() {
        override suspend fun resolve(ctx: Context): Int = ctx.objectValue.getInner() * 3
    }

    @Resolver("fragment _ on Query { inner }")
    class Query_Outer2Resolver : QueryResolvers.Outer2() {
        override suspend fun resolve(ctx: Context): Int = ctx.objectValue.getInner() * 5
    }

    @Resolver
    class Query_Outer3Resolver : QueryResolvers.Outer3() {
        override suspend fun resolve(ctx: Context): Int = ctx.arguments.arg.x * 7
    }

    @Resolver(
        "fragment _ on Query { inner(inp: \$var) } ",
        variables = [ Variable(name = "var", fromArgument = "arg") ]
    )
    class Query_Outer4Resolver : QueryResolvers.Outer4() {
        override suspend fun resolve(ctx: Context): Int = ctx.objectValue.getInner() * 11
    }

    @Resolver
    class Query_InnerResolver : QueryResolvers.Inner() {
        override suspend fun resolve(ctx: Context): Int =
            ctx.arguments.inp?.let { it.x * 2 }
                ?: -1
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
