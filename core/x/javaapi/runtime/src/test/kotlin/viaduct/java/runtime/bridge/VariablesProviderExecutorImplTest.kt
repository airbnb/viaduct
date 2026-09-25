@file:Suppress("ForbiddenImport")

package viaduct.java.runtime.bridge

import graphql.schema.GraphQLInputObjectType
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.bootstrap.FieldEntryConfig
import viaduct.bootstrap.SelectionsBlockConfig
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.VariablesResolver
import viaduct.engine.api.mocks.MockSchema
import viaduct.errors.FrameworkException
import viaduct.java.api.annotations.Variables
import viaduct.java.api.context.VariablesProviderContext
import viaduct.java.api.internal.InputBase
import viaduct.java.api.internal.InternalContext
import viaduct.java.api.types.Arguments
import viaduct.java.api.variables.VariablesProvider
import viaduct.service.api.spi.CodeInjector
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault

class VariablesProviderExecutorImplTest {
    private val schema = MockSchema.mk(
        """
        extend type Query {
            foo(value: Int = 11, input: NestedInput = {value: 13}): Int
            foo_bar(value: Int = 17, input: NestedInput = {value: 23}): Int
            no_arguments: Int
            intermediary(value: Int): Int
        }
        type Under_Score {
            foo(value: Int = 31, input: NestedInput = {value: 37}): Int
            foo_bar(value: Int = 41, input: NestedInput = {value: 43}): Int
        }
        input NestedInput { value: Int }
        """.trimIndent()
    )
    private val engineContext = mockk<EngineExecutionContext> {
        every { fullSchema } returns schema
        every { globalIDCodec } returns GlobalIDCodecDefault
        every { requestContext } returns "request"
    }

    @Test
    fun `provider arguments use ordinary coordinates`() {
        assertArguments("Query", "foo", 11, 13)
    }

    @Test
    fun `provider arguments use field coordinates containing underscores`() {
        assertArguments("Query", "foo_bar", 17, 23)
    }

    @Test
    fun `provider arguments use type coordinates containing underscores`() {
        assertArguments("Under_Score", "foo", 31, 37)
    }

    @Test
    fun `provider arguments use type and field coordinates containing underscores`() {
        assertArguments("Under_Score", "foo_bar", 41, 43)
    }

    private fun assertArguments(
        typeName: String,
        fieldName: String,
        defaultValue: Int,
        defaultNestedValue: Int
    ) = runBlocking {
        val resolver = variablesResolver(typeName, fieldName)

        assertEquals(
            mapOf("value" to 5, "nested" to 7),
            resolver.resolve(
                VariablesResolver.ResolveCtx(mockk(), mapOf("value" to 5, "input" to mapOf("value" to 7))),
                engineContext
            )
        )
        assertEquals(
            mapOf("value" to defaultValue, "nested" to defaultNestedValue),
            resolver.resolve(VariablesResolver.ResolveCtx(mockk(), emptyMap()), engineContext)
        )
    }

    @Test
    fun `invalid field metadata reports its actual coordinate`() =
        runBlocking {
            val resolver = variablesResolver("Under_Score", "missing_field")

            val exception = assertThrows<FrameworkException> {
                resolver.resolve(VariablesResolver.ResolveCtx(mockk(), emptyMap()), engineContext)
            }

            assertEquals("Field Under_Score.missing_field not found.", exception.cause?.message)
        }

    @Test
    fun `invalid type metadata reports its actual type`() =
        runBlocking {
            val resolver = variablesResolver("Missing_Type", "foo_bar")

            val exception = assertThrows<FrameworkException> {
                resolver.resolve(VariablesResolver.ResolveCtx(mockk(), emptyMap()), engineContext)
            }

            assertEquals("Type Missing_Type not in schema.", exception.cause?.message)
        }

    @Test
    @Suppress("DEPRECATION")
    fun `providers without arguments receive the no arguments singleton`() =
        runBlocking {
            for (argumentsClass in listOf(null, Arguments.NoArguments::class.java, Arguments.None::class.java)) {
                val resolver = variablesResolver("Query", "no_arguments", NoArgumentsResolver::class.java, argumentsClass)

                assertEquals(
                    mapOf("value" to 11, "nested" to 13),
                    resolver.resolve(VariablesResolver.ResolveCtx(mockk(), emptyMap()), engineContext)
                )
            }
        }

    private fun variablesResolver(
        typeName: String,
        fieldName: String,
        resolverClass: Class<*> = TypedArgumentsResolver::class.java,
        argumentsClass: Class<out Arguments>? = Query_Foo_bar_Arguments::class.java,
    ): VariablesResolver {
        val entry = FieldEntryConfig(
            typeName = typeName,
            fieldName = fieldName,
            isBatching = false,
            isSelective = false,
            attribution = resolverClass.name,
            querySelections = SelectionsBlockConfig("first: intermediary(value: \$value) second: intermediary(value: \$nested)"),
            tenantAPIData = emptyMap(),
        )
        return RequiredSelectionSetFactory().mkRequiredSelectionSets(
            schema,
            entry,
            resolverClass,
            CodeInjector.Naive,
            argumentsClass
        ).querySelections!!.variablesResolvers.single()
    }

    class TypedArgumentsResolver {
        @Variables(types = ["value: Int", "nested: Int"])
        class Provider : VariablesProvider<Query_Foo_bar_Arguments> {
            override fun provide(context: VariablesProviderContext<Query_Foo_bar_Arguments>): CompletableFuture<Map<String, Any>> {
                val arguments = context.arguments
                val nested = arguments.input()
                assertSame(arguments.context(), nested.context())
                assertEquals("request", context.requestContext)
                return CompletableFuture.completedFuture(mapOf("value" to arguments.value(), "nested" to nested.value()))
            }
        }
    }

    class NoArgumentsResolver {
        @Variables(types = ["value: Int", "nested: Int"])
        class Provider : VariablesProvider<Arguments.NoArguments> {
            override fun provide(context: VariablesProviderContext<Arguments.NoArguments>): CompletableFuture<Map<String, Any>> {
                assertSame(Arguments.None, context.arguments)
                return CompletableFuture.completedFuture(mapOf("value" to 11, "nested" to 13))
            }
        }
    }

    @Suppress("ClassName")
    class Query_Foo_bar_Arguments(context: InternalContext?, data: Map<String, Any?>, type: GraphQLInputObjectType?) :
        InputBase(context, data, type), Arguments {
        fun value(): Int = get("value")!!

        fun input(): NestedInput = getInput("input", ::NestedInput)!!

        fun context(): InternalContext? = __context()
    }

    class NestedInput(context: InternalContext?, data: Map<String, Any?>, type: GraphQLInputObjectType?) : InputBase(context, data, type) {
        fun value(): Int = get("value")!!

        fun context(): InternalContext? = __context()
    }
}
