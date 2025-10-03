@file:Suppress("ForbiddenImport")

package viaduct.tenant.runtime.execution

import graphql.schema.GraphQLInputObjectType
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import viaduct.api.VariablesProvider
import viaduct.api.globalid.GlobalIDCodec
import viaduct.api.internal.InputLikeBase
import viaduct.api.internal.InternalContext
import viaduct.api.internal.ReflectionLoader
import viaduct.api.mocks.MockGlobalID
import viaduct.api.mocks.MockGlobalIDCodec
import viaduct.api.mocks.MockInternalContext
import viaduct.api.mocks.MockReflectionLoader
import viaduct.api.mocks.MockType
import viaduct.api.types.Arguments
import viaduct.api.types.NodeObject
import viaduct.engine.api.VariablesResolver
import viaduct.engine.api.mocks.MockSchema
import viaduct.engine.api.mocks.mkEngineObjectData
import viaduct.tenant.runtime.internal.VariablesProviderInfo

class VariablesProviderExecutorTest {
    private data class MockArgs(val args: Map<String, Any?>) : Arguments {
        val a: Int = args["a"] as Int
        val b: Int = args["b"] as Int
    }

    private val objectData = mkEngineObjectData(MockSchema.minimal.schema.queryType, emptyMap())

    @Test
    fun resolve(): Unit =
        runBlocking {
            val adapter = VariablesProviderExecutor(
                mockk<GlobalIDCodec>(),
                mockk<ReflectionLoader>(),
                variablesProvider = VariablesProviderInfo(setOf("foo", "bar")) {
                    VariablesProvider<MockArgs> { context ->
                        mapOf("foo" to context.args.a * 2, "bar" to context.args.b * 3)
                    }
                }
            ) { args -> MockArgs(args.arguments) }

            assertEquals(
                mapOf("foo" to 10, "bar" to 21),
                adapter.resolve(
                    VariablesResolver.ResolveCtx(
                        objectData,
                        mapOf("a" to 5, "b" to 7),
                        mockk {
                            every { fullSchema } returns MockSchema.minimal
                        }
                    )
                )
            )
        }

    @Test
    fun resolveUnwrapping(): Unit =
        runBlocking {
            class MockInputType(override val context: InternalContext, override val graphQLInputObjectType: GraphQLInputObjectType) : InputLikeBase() {
                override val inputData: Map<String, Any?>
                    get() = mapOf("a" to 10, "b" to 14)
            }
            val globalIDCodec = MockGlobalIDCodec()
            val reflectionLoader = MockReflectionLoader()
            val mockInput = MockInputType(
                MockInternalContext(MockSchema.minimal, globalIDCodec, reflectionLoader),
                GraphQLInputObjectType.newInputObject().name("MockInputType").build()
            )
            val mockGlobalID = MockGlobalID(MockType("User", NodeObject::class), "1234")

            val adapter = VariablesProviderExecutor(
                globalIDCodec,
                reflectionLoader,
                variablesProvider = VariablesProviderInfo(setOf("foo", "bar")) {
                    VariablesProvider<MockArgs> { _ ->
                        mapOf("foo" to mockInput, "bar" to mockGlobalID)
                    }
                }
            ) { args -> MockArgs(args.arguments) }

            assertEquals(
                mapOf("foo" to mapOf("a" to 10, "b" to 14), "bar" to "User:1234"),
                adapter.resolve(
                    VariablesResolver.ResolveCtx(
                        objectData,
                        mapOf("a" to 5, "b" to 7),
                        mockk {
                            every { fullSchema } returns MockSchema.minimal
                        }
                    )
                )
            )
        }
}
