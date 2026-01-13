@file:Suppress("ForbiddenImport")

package viaduct.tenant.runtime.execution

import graphql.schema.GraphQLInputObjectType
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import viaduct.api.VariablesProvider
import viaduct.api.context.VariablesProviderContext
import viaduct.api.globalid.GlobalIDImpl
import viaduct.api.internal.InputLikeBase
import viaduct.api.internal.InternalContext
import viaduct.api.mocks.MockInternalContext
import viaduct.api.mocks.MockReflectionLoader
import viaduct.api.mocks.MockType
import viaduct.api.mocks.testGlobalId
import viaduct.api.types.Arguments
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.VariablesResolver
import viaduct.engine.api.mocks.MockSchema
import viaduct.engine.api.mocks.mkEngineObjectData
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault
import viaduct.tenant.runtime.context.VariablesProviderContextImpl
import viaduct.tenant.runtime.context.factory.VariablesProviderContextFactory
import viaduct.tenant.runtime.internal.InternalContextImpl
import viaduct.tenant.runtime.internal.VariablesProviderInfo

class VariablesProviderExecutorTest {
    private data class MockArgs(val args: Map<String, Any?>) : Arguments {
        val a: Int = args["a"] as Int
        val b: Int = args["b"] as Int
    }

    private val objectData = mkEngineObjectData(MockSchema.minimal.schema.queryType, emptyMap())
    private val reflectionLoader = MockReflectionLoader()
    private val globalIDCodec = GlobalIDCodecDefault

    private inner class TestVariablesProviderContextFactory : VariablesProviderContextFactory {
        override fun createVariablesProviderContext(
            engineExecutionContext: EngineExecutionContext,
            requestContext: Any?,
            rawArguments: Map<String, Any?>
        ): VariablesProviderContext<Arguments> {
            val ic = InternalContextImpl(engineExecutionContext.fullSchema, globalIDCodec, reflectionLoader)
            return VariablesProviderContextImpl(ic, requestContext, MockArgs(rawArguments))
        }
    }

    /**
     * Tests that VariablesProviderExecutor correctly:
     * - Invokes the tenant-defined VariablesProvider
     * - Passes through arguments to the provider (raw map -> MockArgs)
     * - Returns the provider's computed values
     *
     * Does NOT test:
     * - Context creation (delegated to argumentsFactory)
     * - Value unwrapping (provider returns simple ints that need no unwrapping)
     */
    @Test
    fun resolve(): Unit =
        runBlocking {
            val adapter = VariablesProviderExecutor(
                variablesProvider = VariablesProviderInfo(setOf("foo", "bar")) {
                    VariablesProvider<MockArgs> { context ->
                        mapOf("foo" to context.arguments.a * 2, "bar" to context.arguments.b * 3)
                    }
                },
                variablesProviderContextFactory = TestVariablesProviderContextFactory()
            )

            assertEquals(
                mapOf("foo" to 10, "bar" to 21),
                adapter.resolve(
                    VariablesResolver.ResolveCtx(
                        objectData,
                        mapOf("a" to 5, "b" to 7),
                        mockk {
                            every { fullSchema } returns MockSchema.minimal
                            every { requestContext } returns null
                        }
                    )
                )
            )
        }

    /**
     * Tests that VariablesProviderExecutor correctly unwraps special return value types:
     * - InputLikeBase -> unwrapped to its inputData map
     * - GlobalID -> serialized to string format via globalIDCodec
     *
     * Does NOT test:
     * - Context creation (delegated to argumentsFactory)
     * - The VariablesProvider logic itself (just returns static test values)
     */
    @Test
    fun resolveUnwrapping(): Unit =
        runBlocking {
            class MockInputType(override val context: InternalContext, override val graphQLInputObjectType: GraphQLInputObjectType) : InputLikeBase() {
                override val inputData: Map<String, Any?>
                    get() = mapOf("a" to 10, "b" to 14)
            }
            val mockInput = MockInputType(
                MockInternalContext(MockSchema.minimal, globalIDCodec, reflectionLoader),
                GraphQLInputObjectType.newInputObject().name("MockInputType").build()
            )
            val userType = MockType.mkNodeObject("User")
            val mockGlobalID = GlobalIDImpl(userType, "1234")

            val adapter = VariablesProviderExecutor(
                variablesProvider = VariablesProviderInfo(setOf("foo", "bar")) {
                    VariablesProvider<MockArgs> { _ ->
                        mapOf("foo" to mockInput, "bar" to mockGlobalID)
                    }
                },
                variablesProviderContextFactory = TestVariablesProviderContextFactory()
            )

            assertEquals(
                mapOf("foo" to mapOf("a" to 10, "b" to 14), "bar" to userType.testGlobalId("1234")),
                adapter.resolve(
                    VariablesResolver.ResolveCtx(
                        objectData,
                        mapOf("a" to 5, "b" to 7),
                        mockk {
                            every { fullSchema } returns MockSchema.minimal
                            every { requestContext } returns null
                        }
                    )
                )
            )
        }
}
