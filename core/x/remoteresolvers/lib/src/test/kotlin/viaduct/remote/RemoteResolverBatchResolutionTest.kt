package viaduct.remote

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.api.mocks.MockSchema
import viaduct.engine.runtime.mocks.ContextMocks
import viaduct.remote.api.spi.RemoteResolverExecutionInstrumentation
import viaduct.remote.api.spi.RemoteResolverFunction
import viaduct.remote.fixtures.SimpleNodeResolverExecutor
import viaduct.remote.grpc.Selector
import viaduct.remote.registry.NodeExecutorRegistry

/** Unit tests for [resolveNodeExecutorBatch]'s [RemoteResolverExecutionInstrumentation] wiring, shared by the unary and streaming transports. */
class RemoteResolverBatchResolutionTest {
    private val testSchema = MockSchema.mk(
        """
        extend type Query { test: String }
        type User {
            id: ID!
            name: String!
            email: String!
        }
        """.trimIndent()
    )

    @AfterEach
    fun tearDown() {
        NodeExecutorRegistry.clear()
    }

    @Test
    fun `invokes the instrumentation with the executor's resolver metadata`() =
        runTest {
            val executor = SimpleNodeResolverExecutor.createUserResolver()
            val executorId = NodeExecutorRegistry.register(executor)
            val context = ContextMocks(testSchema).engineExecutionContext
            var capturedMetadata: viaduct.engine.api.ResolverMetadata? = null
            val recording = object : RemoteResolverExecutionInstrumentation {
                override suspend fun <T> instrumentRemoteResolverExecution(
                    resolver: RemoteResolverFunction<T>,
                    parameters: RemoteResolverExecutionInstrumentation.RemoteResolverExecutionParameters,
                ): T {
                    capturedMetadata = parameters.resolverMetadata
                    return resolver.resolve()
                }
            }

            val results = resolveNodeExecutorBatch(
                executorId,
                listOf(Selector.newBuilder().setId("user:1").build()),
                context,
                recording,
            )

            assertEquals(executor.metadata, capturedMetadata)
            assertEquals(1, results.size)
            assertFalse(results.first().hasError(), "expected a successful resolution")
        }

    @Test
    fun `an exception thrown by the instrumentation itself is caught and reported per-selector`() =
        runTest {
            val executor = SimpleNodeResolverExecutor.createUserResolver()
            val executorId = NodeExecutorRegistry.register(executor)
            val context = ContextMocks(testSchema).engineExecutionContext
            val throwing = object : RemoteResolverExecutionInstrumentation {
                override suspend fun <T> instrumentRemoteResolverExecution(
                    resolver: RemoteResolverFunction<T>,
                    parameters: RemoteResolverExecutionInstrumentation.RemoteResolverExecutionParameters,
                ): T = throw IllegalStateException("instrumentation failure")
            }

            val results = resolveNodeExecutorBatch(
                executorId,
                listOf(
                    Selector.newBuilder().setId("user:1").build(),
                    Selector.newBuilder().setId("user:2").build(),
                ),
                context,
                throwing,
            )

            assertEquals(2, results.size)
            results.forEach { assertTrue(it.hasError(), "expected every selector to report the instrumentation's failure") }
        }
}
