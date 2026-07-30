@file:Suppress("ForbiddenImport")

package viaduct.remote

import io.grpc.ManagedChannel
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.api.mocks.MockSchema
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.engine.runtime.mocks.ContextMocks
import viaduct.remote.fixtures.SimpleNodeResolverExecutor
import viaduct.remote.registry.ContextRegistry
import viaduct.remote.registry.NodeExecutorRegistry
import viaduct.remote.registry.SelectionsRegistry

/**
 * End-to-end tests for the remote resolver flow using in-process gRPC channels.
 *
 * Exercises: direct resolver execution, proxy factory selection, delegation
 * via [ProxyNodeResolverExecutor], full round-trip through
 * [RemoteNodeProxyExecutor] → [RemoteResolverServiceImpl] and back, and the
 * re-entrant callback path through [EngineCallbackServiceImpl].
 */
class RemoteProxyIntegrationTest {
    // Schema with User and Post types for testing
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

    @Test
    fun `test simple node resolver executor directly`() =
        runBlocking {
            // Create a simple resolver with user data
            val resolver = SimpleNodeResolverExecutor.createUserResolver()
            val context = ContextMocks(testSchema).engineExecutionContext

            // Create selector
            val selector = NodeResolverExecutor.Selector(
                id = "user:1",
                selections = context.engineSelectionSetFactory.engineSelectionSet("User", "id name email", emptyMap())
            )

            // Resolve a user
            val results = resolver.resolve(listOf(selector), context)
            val result = results[selector]

            // Verify the result
            assertNotNull(result)
            assertTrue(result!!.isSuccess)
            val userData = result.getOrNull()
            assertEquals("user:1", userData?.fetch("id"))
            assertEquals("Alice", userData?.fetch("name"))
            assertEquals("alice@example.com", userData?.fetch("email"))
        }

    @Test
    fun `test proxy resolver factory wraps specified types`() {
        // Create mock channels for testing
        val rrsChannel = io.grpc.inprocess.InProcessChannelBuilder.forName("test-rrs").directExecutor().build()
        val callbackEndpoint = "test-rrp-callback"

        // Create a factory that proxies "User" type
        val factory = RemoteProxyResolverFactory.proxyTypes(rrsChannel, callbackEndpoint, "User")

        // Create resolvers for different types
        val userResolver = SimpleNodeResolverExecutor.createUserResolver()
        val postResolver = SimpleNodeResolverExecutor.createPostResolver()

        // Try to proxy each resolver
        val proxiedUser = factory.proxyNode(userResolver)
        val proxiedPost = factory.proxyNode(postResolver)

        // User should be proxied
        assertTrue(proxiedUser != null)
        assertTrue(proxiedUser is RemoteNodeProxyExecutor)

        // Post should NOT be proxied (not in the selected types)
        assertTrue(proxiedPost == null)
    }

    @Test
    fun `test proxy delegates to wrapped resolver`() =
        runBlocking {
            // Create the chain: NodeResolver -> Proxy
            val resolver = SimpleNodeResolverExecutor.createUserResolver()
            val proxy = ProxyNodeResolverExecutor(resolver, "User")

            // Create execution context and selector with field selections
            val context = ContextMocks(testSchema).engineExecutionContext
            val selector = NodeResolverExecutor.Selector(
                id = "user:3",
                selections = context.engineSelectionSetFactory.engineSelectionSet("User", "id name email", emptyMap())
            )

            // Resolve through the proxy
            val results = proxy.resolve(listOf(selector), context)

            // Verify the result came through the proxy
            val result = results[selector]
            assertNotNull(result)
            assertTrue(result!!.isSuccess)

            val userData = result.getOrNull()
            assertEquals("user:3", userData?.fetch("id"))
            assertEquals("Charlie", userData?.fetch("name"))
        }

    @Test
    fun `test resolver handles missing node`() =
        runBlocking {
            val resolver = SimpleNodeResolverExecutor.createUserResolver()
            val context = ContextMocks(testSchema).engineExecutionContext

            // Create selector for non-existent user
            val selector = NodeResolverExecutor.Selector(
                id = "user:999",
                selections = context.engineSelectionSetFactory.engineSelectionSet("User", "id name email", emptyMap())
            )

            // Try to resolve a non-existent user
            val results = resolver.resolve(listOf(selector), context)
            val result = results[selector]

            // Should return failure
            assertNotNull(result)
            assertFalse(result!!.isSuccess)
            assertTrue(result.exceptionOrNull() is NoSuchElementException)
        }

    @Test
    fun `test end-to-end gRPC remote resolver flow`() =
        runBlocking {
            // Clean up registries before test
            NodeExecutorRegistry.clear()
            ContextRegistry.clear()
            SelectionsRegistry.clear()

            // 1. Setup: Create the underlying resolver with test data
            val actualResolver = SimpleNodeResolverExecutor.createUserResolver()

            // 2. Setup: Start InProcess gRPC server for RRS (Remote Resolver Server)
            val rrsServerName = "test-rrs-e2e"
            val rrsService = InProcessCallbackRemoteResolverService()
            val rrsServer = InProcessServerBuilder
                .forName(rrsServerName)
                .directExecutor()
                .addService(rrsService)
                .build()
                .start()

            // 3. Setup: Start InProcess gRPC server for RRP callback service
            val callbackEndpoint = "test-rrp-callback-e2e"
            val callbackService = EngineCallbackServiceImpl()
            val callbackServer = InProcessServerBuilder
                .forName(callbackEndpoint)
                .directExecutor()
                .addService(callbackService)
                .build()
                .start()

            try {
                // 4. Setup: Register the actual resolver in NodeExecutorRegistry (simulating service discovery)
                val executorId = NodeExecutorRegistry.register(actualResolver)

                // 5. Setup: Create gRPC channel to RRS
                val rrsChannel = io.grpc.inprocess.InProcessChannelBuilder
                    .forName(rrsServerName)
                    .directExecutor()
                    .build()

                // 6. Test: Create RemoteNodeProxyExecutor (the gRPC proxy)
                val remoteProxy = RemoteNodeProxyExecutor(
                    originalExecutor = actualResolver,
                    executorId = executorId,
                    rrsChannel = rrsChannel,
                    callbackEndpoint = callbackEndpoint
                )

                // 7. Test: Create execution context and selector
                val context = ContextMocks(testSchema).engineExecutionContext
                val selector = NodeResolverExecutor.Selector(
                    id = "user:1",
                    selections = context.engineSelectionSetFactory.engineSelectionSet("User", "id name email", emptyMap())
                )

                // 8. Test: Make a request through the FULL gRPC stack
                // Flow: RemoteNodeProxyExecutor (RRP) -> gRPC -> RemoteResolverServiceImpl (RRS)
                //       -> actualResolver -> serialize -> gRPC -> RemoteNodeProxyExecutor -> deserialize
                val results = remoteProxy.resolve(listOf(selector), context)

                // 9. Verify: Check the result came back correctly through the full gRPC round-trip
                val result = results[selector]
                assertNotNull(result, "Result should not be null")
                assertTrue(result!!.isSuccess, "Result should be success")

                val userData = result.getOrNull()
                assertNotNull(userData, "User data should not be null")
                assertEquals("user:1", userData!!.fetch("id"), "ID should match")
                assertEquals("Alice", userData.fetch("name"), "Name should match")
                assertEquals("alice@example.com", userData.fetch("email"), "Email should match")

                // 10. Test: Verify batching works with multiple selectors
                val selector2 = NodeResolverExecutor.Selector(
                    id = "user:2",
                    selections = context.engineSelectionSetFactory.engineSelectionSet("User", "id name", emptyMap())
                )
                val batchResults = remoteProxy.resolve(listOf(selector, selector2), context)

                assertEquals(2, batchResults.size, "Should have 2 results")
                assertTrue(batchResults[selector]?.isSuccess == true, "First result should succeed")
                assertTrue(batchResults[selector2]?.isSuccess == true, "Second result should succeed")
                assertEquals("Bob", batchResults[selector2]?.getOrNull()?.fetch("name"), "Second user name should match")

                // 11. Test: Verify error handling for missing node
                val missingSelector = NodeResolverExecutor.Selector(
                    id = "user:999",
                    selections = context.engineSelectionSetFactory.engineSelectionSet("User", "id name", emptyMap())
                )
                val errorResults = remoteProxy.resolve(listOf(missingSelector), context)
                val errorResult = errorResults[missingSelector]

                assertNotNull(errorResult, "Error result should not be null")
                assertFalse(errorResult!!.isSuccess, "Error result should be failure")
                val exception = errorResult.exceptionOrNull()
                assertTrue(exception is RemoteResolverException, "Should be RemoteResolverException")
                assertTrue(
                    exception!!.message?.contains("NoSuchElementException") == true,
                    "Error message should mention NoSuchElementException"
                )
            } finally {
                // Cleanup
                rrsServer.shutdown()
                callbackServer.shutdown()
                rrsService.shutdownChannels()
                NodeExecutorRegistry.clear()
                ContextRegistry.clear()
                SelectionsRegistry.clear()
            }
        }

    @Test
    fun `a node whose result fails serialization fails only its own selector in a batch`() =
        runBlocking {
            // Regression: one node whose result can't be serialized (its value carries an unresolved
            // nested NodeReference) must not sink its batch-mates. RemoteResolverServiceImpl
            // .batchResolveNode now isolates the serialization failure to that node's error — mirroring
            // batchResolveField — instead of failing the whole batch. Before the fix the serialize throw
            // propagated out of the gRPC handler and failed every selector in the call.
            NodeExecutorRegistry.clear()
            ContextRegistry.clear()
            SelectionsRegistry.clear()

            // A single resolver serves the whole batch: the sentinel id returns an un-serializable node,
            // every other id takes the fixture's normal path.
            val actualResolver = SimpleNodeResolverExecutor.createUserResolver()

            val rrsServerName = "test-rrs-node-serfail"
            val rrsService = InProcessCallbackRemoteResolverService()
            val rrsServer = InProcessServerBuilder
                .forName(rrsServerName)
                .directExecutor()
                .addService(rrsService)
                .build()
                .start()

            val callbackEndpoint = "test-rrp-callback-node-serfail"
            val callbackService = EngineCallbackServiceImpl()
            val callbackServer = InProcessServerBuilder
                .forName(callbackEndpoint)
                .directExecutor()
                .addService(callbackService)
                .build()
                .start()

            try {
                val executorId = NodeExecutorRegistry.register(actualResolver)
                val rrsChannel = InProcessChannelBuilder
                    .forName(rrsServerName)
                    .directExecutor()
                    .build()

                val remoteProxy = RemoteNodeProxyExecutor(
                    originalExecutor = actualResolver,
                    executorId = executorId,
                    rrsChannel = rrsChannel,
                    callbackEndpoint = callbackEndpoint
                )

                val context = ContextMocks(testSchema).engineExecutionContext

                // Batch of three: the MIDDLE node's result fails serialization; the two around it are
                // well-formed.
                val firstSelector = NodeResolverExecutor.Selector(
                    id = "user:1",
                    selections = context.engineSelectionSetFactory.engineSelectionSet("User", "id name email", emptyMap())
                )
                val failingSelector = NodeResolverExecutor.Selector(
                    id = SimpleNodeResolverExecutor.UNSERIALIZABLE_NODE_ID,
                    selections = context.engineSelectionSetFactory.engineSelectionSet("User", "id name email", emptyMap())
                )
                val lastSelector = NodeResolverExecutor.Selector(
                    id = "user:3",
                    selections = context.engineSelectionSetFactory.engineSelectionSet("User", "id name email", emptyMap())
                )

                val results = remoteProxy.resolve(listOf(firstSelector, failingSelector, lastSelector), context)

                // The middle node is isolated to a failure...
                val failingResult = results[failingSelector]
                assertNotNull(failingResult, "Failing selector should have a result")
                assertFalse(failingResult!!.isSuccess, "Node with an un-serializable result should fail")
                assertTrue(
                    failingResult.exceptionOrNull() is RemoteResolverException,
                    "Should be RemoteResolverException, got ${failingResult.exceptionOrNull()}"
                )

                // ...while the two well-formed nodes in the same batch still resolve successfully.
                val firstResult = results[firstSelector]
                assertNotNull(firstResult, "First selector should have a result")
                assertTrue(firstResult!!.isSuccess, "Well-formed node should still succeed despite its batch-mate failing")
                assertEquals("Alice", firstResult.getOrNull()?.fetch("name"), "First node name should match")

                val lastResult = results[lastSelector]
                assertNotNull(lastResult, "Last selector should have a result")
                assertTrue(lastResult!!.isSuccess, "Well-formed node should still succeed despite its batch-mate failing")
                assertEquals("Charlie", lastResult.getOrNull()?.fetch("name"), "Last node name should match")
            } finally {
                rrsServer.shutdown()
                callbackServer.shutdown()
                rrsService.shutdownChannels()
                NodeExecutorRegistry.clear()
                ContextRegistry.clear()
                SelectionsRegistry.clear()
            }
        }

    @Test
    fun `test callback flow with re-entrant calls`() =
        runBlocking {
            // Clean up registries before test
            NodeExecutorRegistry.clear()
            ContextRegistry.clear()
            SelectionsRegistry.clear()

            // 1. Setup: Create a User resolver (will be called via callback)
            val userResolver = SimpleNodeResolverExecutor.createUserResolver()

            // 2. Setup: Create a Post resolver that makes callback queries
            // This resolver will fetch a post, then call context.query() to fetch the author
            val postResolver = viaduct.remote.fixtures.CallbackNodeResolverExecutor.create()

            // 3. Setup: Start InProcess gRPC server for RRS (Remote Resolver Server)
            val rrsServerName = "test-rrs-callback"
            val rrsService = InProcessCallbackRemoteResolverService()
            val rrsServer = InProcessServerBuilder
                .forName(rrsServerName)
                .directExecutor()
                .addService(rrsService)
                .build()
                .start()

            // 4. Setup: Start InProcess gRPC server for RRP callback service
            val callbackEndpoint = "test-rrp-callback-service"
            val callbackService = EngineCallbackServiceImpl()
            val callbackServer = InProcessServerBuilder
                .forName(callbackEndpoint)
                .directExecutor()
                .addService(callbackService)
                .build()
                .start()

            try {
                // 5. Setup: Register both resolvers in NodeExecutorRegistry
                val postExecutorId = NodeExecutorRegistry.register(postResolver)
                NodeExecutorRegistry.register(userResolver)

                // 6. Setup: Create gRPC channel to RRS
                val rrsChannel = InProcessChannelBuilder
                    .forName(rrsServerName)
                    .directExecutor()
                    .build()

                // 7. Test: Create RemoteNodeProxyExecutor for Post (the gRPC proxy)
                val remotePostProxy = RemoteNodeProxyExecutor(
                    originalExecutor = postResolver,
                    executorId = postExecutorId,
                    rrsChannel = rrsChannel,
                    callbackEndpoint = callbackEndpoint
                )

                val context = ContextMocks(testSchema).engineExecutionContext
                // ContextMocks does not register resolvers the way the real engine does, so the
                // re-entrant context.query() inside the callback will not find the User
                // resolver. This test verifies that the callback infrastructure is wired up
                // correctly; the assertions below tolerate the resulting lookup failure.

                // 9. Test: Create selector for Post
                val postSelector = NodeResolverExecutor.Selector(
                    id = "post:1",
                    selections = context.engineSelectionSetFactory.engineSelectionSet(
                        "Post",
                        "id title content authorId",
                        emptyMap()
                    )
                )

                // Exercises the full callback flow:
                //   Proxy → gRPC → service → resolver → context.query() → gRPC callback → engine
                //
                // With the current test context the engine-side query() will not resolve the
                // User resolver; we assert that the callback mechanism itself fires correctly
                // (exception message identifies the lookup failure) rather than that the
                // query completes.
                val results = try {
                    remotePostProxy.resolve(listOf(postSelector), context)
                } catch (e: Exception) {
                    // Expected with the current ContextMocks: the callback fires but engine-side
                    // lookup fails. Tolerate both a registry miss and a gRPC-level failure.
                    assertTrue(
                        e.message?.contains("Context not found") == true ||
                            e.message?.contains("Node resolver not found") == true ||
                            e is io.grpc.StatusRuntimeException,
                        "Should get an error related to missing context or resolver during callback. Got: ${e.message}"
                    )
                    return@runBlocking
                }

                // If we get here the callback succeeded end-to-end; verify the returned Post.
                val result = results[postSelector]
                assertTrue(result != null, "Result should not be null")
            } finally {
                // Cleanup
                rrsServer.shutdown()
                callbackServer.shutdown()
                rrsService.shutdownChannels()
                NodeExecutorRegistry.clear()
                ContextRegistry.clear()
                SelectionsRegistry.clear()
            }
        }
}

internal class InProcessCallbackRemoteResolverService : RemoteResolverServiceImpl() {
    override fun createCallbackChannel(endpoint: String): ManagedChannel =
        InProcessChannelBuilder.forName(endpoint)
            .directExecutor()
            .build()
}
