@file:Suppress("unused", "ClassName")

package viaduct.tenant.runtime.execution.variables.trivial

import org.junit.jupiter.api.Test
import viaduct.api.Resolver
import viaduct.api.Variable
import viaduct.api.Variables
import viaduct.api.VariablesProvider
import viaduct.api.context.VariablesProviderContext
import viaduct.api.types.Arguments
import viaduct.graphql.test.assertEquals
import viaduct.tenant.runtime.execution.variables.trivial.resolverbases.QueryResolvers
import viaduct.tenant.runtime.fixtures.FeatureAppTestBase

class VariablesProviderFeatureAppTest : FeatureAppTestBase() {
    override var sdl =
        """
        | #START_SCHEMA
        | extend type Query {
        |   fromArgumentField(arg: Int!): Int @resolver
        |   intermediary(arg: Int!): Int @resolver
        |   intermediaryTakesInput(input: MyInput!): Int @resolver
        |   intermediaryTakesGlobalID(input: ID!): String @resolver
        |   intermediaryTakesNestedComplexInput(input: InputWithNestedInput!): String @resolver
        |   fromVariablesProvider: Int @resolver
        |   fromVariablesProviderWithInput: Int @resolver
        |   fromVariablesProviderWithGlobalID: String @resolver
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

    @Resolver(
        """
        fragment _ on Query {
           intermediary(arg: ${'$'}myVar)
        }
        """,
        variables = [Variable("myVar", fromArgument = "arg")]
    )
    class Query_FromArgumentFieldResolver : QueryResolvers.FromArgumentField() {
        override suspend fun resolve(ctx: Context): Int = ctx.objectValue.get("intermediary", Int::class)
    }

    @Resolver
    class Query_IntermediaryResolver : QueryResolvers.Intermediary() {
        override suspend fun resolve(ctx: Context): Int = ctx.arguments.arg
    }

    @Resolver
    class Query_IntermediaryTakesInputResolver : QueryResolvers.IntermediaryTakesInput() {
        override suspend fun resolve(ctx: Context): Int = ctx.arguments.input.x
    }

    @Resolver
    class Query_IntermediaryTakesGlobalIDResolver : QueryResolvers.IntermediaryTakesGlobalID() {
        override suspend fun resolve(ctx: Context): String = ctx.arguments.input
    }

    @Resolver
    class Query_IntermediaryTakesNestedComplexInputResolver : QueryResolvers.IntermediaryTakesNestedComplexInput() {
        override suspend fun resolve(ctx: Context): String {
            val input = ctx.arguments.input
            return "Color: ${input.complexInput.color}, Values: ${input.complexInput.intArray.joinToString(",")}"
        }
    }

    @Resolver(
        """
        fragment _ on Query {
            intermediary(arg: ${'$'}x)
        }
        """
    )
    class Query_FromVariablesProviderResolver : QueryResolvers.FromVariablesProvider() {
        override suspend fun resolve(ctx: Context): Int = ctx.objectValue.get("intermediary", Int::class)

        @Variables("x: Int!")
        class TestVariablesProvider : VariablesProvider<Arguments> {
            override suspend fun provide(context: VariablesProviderContext<Arguments>): Map<String, Any?> = mapOf("x" to 123)
        }
    }

    @Resolver(
        """
        fragment _ on Query {
            intermediaryTakesInput(input: ${'$'}x)
        }
        """
    )
    class Query_FromVariablesProviderWithInputResolver : QueryResolvers.FromVariablesProviderWithInput() {
        override suspend fun resolve(ctx: Context): Int = ctx.objectValue.get("intermediaryTakesInput", Int::class)

        @Variables("x: MyInput!")
        class TestVariablesProvider : VariablesProvider<Arguments> {
            override suspend fun provide(context: VariablesProviderContext<Arguments>): Map<String, Any?> {
                return mapOf("x" to MyInput.Builder(context).x(456).build())
            }
        }
    }

    @Resolver(
        """
        fragment _ on Query {
            intermediaryTakesGlobalID(input: ${'$'}x)
        }
        """
    )
    class Query_FromVariablesProviderWithGlobalIDResolver : QueryResolvers.FromVariablesProviderWithGlobalID() {
        override suspend fun resolve(ctx: Context): String = ctx.objectValue.get("intermediaryTakesGlobalID", String::class)

        @Variables("x: ID!")
        class TestVariablesProvider : VariablesProvider<Arguments> {
            override suspend fun provide(context: VariablesProviderContext<Arguments>): Map<String, Any?> {
                return mapOf("x" to context.globalIDFor(MyType.Reflection, "123"))
            }
        }
    }

    @Resolver(
        """
        fragment _ on Query {
            intermediaryTakesNestedComplexInput(input: ${'$'}x)
        }
        """
    )
    class Query_FromVariablesProviderWithNestedComplexInputResolver : QueryResolvers.FromVariablesProviderWithNestedComplexInput() {
        override suspend fun resolve(ctx: Context): String = ctx.objectValue.get("intermediaryTakesNestedComplexInput", String::class)

        @Variables("x: InputWithNestedInput!")
        class TestVariablesProvider : VariablesProvider<Arguments> {
            override suspend fun provide(context: VariablesProviderContext<Arguments>): Map<String, Any?> {
                val complexInput = ComplexInput.Builder(context)
                    .color(Color.RED)
                    .intArray(listOf(1, 2, 3))
                    .build()
                return mapOf("x" to InputWithNestedInput.Builder(context).complexInput(complexInput).build())
            }
        }
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
