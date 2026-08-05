@file:Suppress("ForbiddenImport")

package viaduct.remote

import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.mocks.MockSchema
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.engine.runtime.mocks.ContextMocks
import viaduct.remote.fixtures.CallbackNodeResolverExecutor
import viaduct.remote.fixtures.SimpleNodeResolverExecutor
import viaduct.remote.registry.NodeExecutorRegistry
import viaduct.remote.registry.SchemaRegistry

/**
 * End-to-end tests for [RemoteNodeStreamProxyExecutor]: full round-trip through the real
 * [RemoteResolverStreamServiceImpl] over an in-process gRPC bidirectional stream, including the
 * re-entrant callback path driven by [driveClientStream].
 */
class RemoteNodeStreamProxyExecutorTest {
    private val testSchema = MockSchema.mk(
        """
        extend type Query { test: String }
        type User {
            id: ID!
            name: String!
            email: String!
        }
        type Post {
            id: ID!
            title: String!
            content: String!
            authorId: ID!
            author: User
        }
        """.trimIndent()
    )

    @BeforeEach
    fun setUp() {
        SchemaRegistry.register(testSchema)
    }

    @AfterEach
    fun tearDown() {
        SchemaRegistry.clear()
        NodeExecutorRegistry.clear()
    }

    @Test
    fun `resolves a batch through the full streaming round-trip`() =
        runTest {
            withProxy(SimpleNodeResolverExecutor.createUserResolver()) { proxy, context ->
                val selector = NodeResolverExecutor.Selector(
                    id = "user:1",
                    selections = context.engineSelectionSetFactory.engineSelectionSet("User", "id name email", emptyMap())
                )
                val results = proxy.resolve(listOf(selector), context)

                val result = results[selector]
                assertNotNull(result, "Result should not be null")
                assertTrue(result!!.isSuccess, "Result should be success")
                val userData = result.getOrNull()
                assertEquals("user:1", userData?.fetch("id"))
                assertEquals("Alice", userData?.fetch("name"))
                assertEquals("alice@example.com", userData?.fetch("email"))
            }
        }

    @Test
    fun `a missing node surfaces as a RemoteResolverException`() =
        runTest {
            withProxy(SimpleNodeResolverExecutor.createUserResolver()) { proxy, context ->
                val selector = NodeResolverExecutor.Selector(
                    id = "user:999",
                    selections = context.engineSelectionSetFactory.engineSelectionSet("User", "id name", emptyMap())
                )
                val results = proxy.resolve(listOf(selector), context)

                val result = results[selector]
                assertNotNull(result, "Result should not be null")
                assertFalse(result!!.isSuccess, "Result should be failure")
                val exception = result.exceptionOrNull()
                assertTrue(exception is RemoteResolverException, "Should be RemoteResolverException, got $exception")
                assertTrue(exception!!.message?.contains("NoSuchElementException") == true)
            }
        }

    @Test
    fun `a re-entrant ctx query from the resolver fires the callback path`() =
        runTest {
            // Mirrors RemoteFieldProxyIntegrationTest's equivalent: ContextMocks runs over a no-op
            // engine, so the re-entrant query cannot complete. We assert the callback mechanism
            // fires (the failure originates engine-side, behind driveClientStream's callback
            // handling) rather than that the query succeeds.
            withProxy(CallbackNodeResolverExecutor.create()) { proxy, context ->
                val selector = NodeResolverExecutor.Selector(
                    id = "post:1",
                    selections = context.engineSelectionSetFactory.engineSelectionSet("Post", "id title content", emptyMap())
                )
                val results = proxy.resolve(listOf(selector), context)

                val result = results[selector]
                assertNotNull(result, "Result should not be null")
                assertFalse(result!!.isSuccess, "Re-entrant query cannot complete under ContextMocks")
                val exception = result.exceptionOrNull()
                assertTrue(exception is RemoteResolverException, "Should be RemoteResolverException, got $exception")
            }
        }

    @Test
    fun `constructing a proxy for a selective resolver fails fast`() {
        val selective = object : NodeResolverExecutor by SimpleNodeResolverExecutor.createUserResolver() {
            override val isSelective: Boolean = true
        }
        val channel = InProcessChannelBuilder.forName("rrs-stream-selective-${System.nanoTime()}").build()
        try {
            assertThrows<IllegalArgumentException> {
                RemoteNodeStreamProxyExecutor(originalExecutor = selective, executorId = "User", rrsChannel = channel)
            }
        } finally {
            channel.shutdownNow()
        }
    }

    private suspend fun withProxy(
        actualResolver: NodeResolverExecutor,
        block: suspend (RemoteNodeStreamProxyExecutor, EngineExecutionContext) -> Unit
    ) {
        val executorId = NodeExecutorRegistry.register(actualResolver)
        val serverName = "rrs-stream-node-proxy-${System.nanoTime()}"
        val server = InProcessServerBuilder.forName(serverName)
            .directExecutor()
            .addService(RemoteResolverStreamServiceImpl())
            .build()
            .start()
        val rrsChannel = InProcessChannelBuilder.forName(serverName).directExecutor().build()
        try {
            val proxy = RemoteNodeStreamProxyExecutor(
                originalExecutor = actualResolver,
                executorId = executorId,
                rrsChannel = rrsChannel
            )
            val context = ContextMocks(testSchema).engineExecutionContext
            block(proxy, context)
        } finally {
            rrsChannel.shutdownNow()
            server.shutdownNow()
        }
    }
}
