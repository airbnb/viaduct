package viaduct.tenant.runtime.context.factory

import org.junit.jupiter.api.Test
import viaduct.api.Resolver
import viaduct.api.Variables
import viaduct.api.VariablesProvider
import viaduct.api.context.VariablesProviderContext
import viaduct.api.types.Arguments
import viaduct.graphql.test.assertEquals
import viaduct.tenant.runtime.context.factory.resolverbases.QueryResolvers
import viaduct.tenant.runtime.fixtures.FeatureAppTestBase

class ContextFactoryFeatureAppTest : FeatureAppTestBase() {
    override var sdl =
        """
        #START_SCHEMA
        extend type Query {
            valueFromArg(arg: Int): Int @resolver
            reqCtxFromFieldCtx: Int @resolver
            reqCtxFromVariableCtx: Int @resolver
        }

        type Foo {
          fieldWithArgs(x: Int, y: Boolean!, z: String = ""): Int
        }

        type Bar {
          x: Int
          y: Boolean!
          z: String
          bar: Bar
          bars: [Bar]
        }

        type Baz implements Node @resolver {
          id: ID!
          x: Int
        }

        extend type Mutation {
          mutate(x: Int!): Int!
        }

        input Input {
          x: Int
          y: Boolean!
          z: String = ""
        }
        #END_SCHEMA
        """

    val bazID = createGlobalIdString(Baz.Reflection, "ignore me")

    @Resolver
    class BazNodeResolver : NodeResolvers.Baz() {
        override suspend fun resolve(ctx: Context): Baz = Baz.Builder(ctx).x(ctx.requestContext as Int).build()
    }

    @Resolver
    class Query_ValueFromArg : QueryResolvers.ValueFromArg() {
        override suspend fun resolve(ctx: Context) = ctx.arguments.arg
    }

    @Resolver
    class Query_ReqCtxFromFieldCtx : QueryResolvers.ReqCtxFromFieldCtx() {
        override suspend fun resolve(ctx: Context) = ctx.requestContext as Int
    }

    @Resolver(" valueFromArg(arg:\$var) ")
    class Query_ReqCtxFromVariableCtx : QueryResolvers.ReqCtxFromVariableCtx() {
        override suspend fun resolve(ctx: Context) = ctx.objectValue.getValueFromArg() as Int

        @Variables("var: Int!")
        class VarProvider : VariablesProvider<Arguments> {
            override suspend fun provide(context: VariablesProviderContext<Arguments>) = mapOf("var" to context.requestContext)
        }
    }

    @Test
    fun `request context gets to field execution context`() {
        execute("query { reqCtxFromFieldCtx }", requestContext = 42)
            .assertEquals {
                "data" to {
                    "reqCtxFromFieldCtx" to 42
                }
            }
    }

    @Test
    fun `request context gets to node execution context`() {
        val bazID = createGlobalIdString(Baz.Reflection, "123")
        execute("query { node(id: \"$bazID\") { ... on Baz { x } } }", requestContext = 42)
            .assertEquals {
                "data" to {
                    "node" to {
                        "x" to 42
                    }
                }
            }
    }

    @Test
    fun `request context gets to variable execution context`() {
        execute("query { reqCtxFromVariableCtx }", requestContext = 42)
            .assertEquals {
                "data" to {
                    "reqCtxFromVariableCtx" to 42
                }
            }
    }
}
