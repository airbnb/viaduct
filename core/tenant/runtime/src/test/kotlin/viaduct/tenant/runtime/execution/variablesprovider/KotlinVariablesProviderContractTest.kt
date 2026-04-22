@file:Suppress("unused", "ClassName")

package viaduct.tenant.runtime.execution.variablesprovider

import viaduct.api.Resolver
import viaduct.api.Variable
import viaduct.api.Variables
import viaduct.api.VariablesProvider
import viaduct.api.context.VariablesProviderContext
import viaduct.api.types.Arguments
import viaduct.tenant.runtime.execution.variablesprovider.resolverbases.QueryResolvers

class KotlinVariablesProviderContractTest : VariablesProviderContractTest() {
    @Resolver(
        """
        fragment _ on Query {
           intermediary(arg: ${'$'}myVar)
        }
        """,
        variables = [Variable("myVar", fromArgument = "arg")]
    )
    class Query_FromArgumentFieldResolver : QueryResolvers.FromArgumentField() {
        override suspend fun resolve(ctx: Context): Int = ctx.getObjectValue().get("intermediary", Int::class)
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
        override suspend fun resolve(ctx: Context): Int = ctx.getObjectValue().get("intermediary", Int::class)

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
        override suspend fun resolve(ctx: Context): Int = ctx.getObjectValue().get("intermediaryTakesInput", Int::class)

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
        override suspend fun resolve(ctx: Context): String = ctx.getObjectValue().get("intermediaryTakesGlobalID", String::class)

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
        override suspend fun resolve(ctx: Context): String = ctx.getObjectValue().get("intermediaryTakesNestedComplexInput", String::class)

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
}
