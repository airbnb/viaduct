package viaduct.remote.config

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.ResolverMetadata
import viaduct.engine.api.spi.ProxyResolverFactory
import viaduct.remote.RemoteNodeStreamProxyExecutor
import viaduct.remote.UnaryRemoteNodeProxyExecutor
import viaduct.remote.fixtures.SimpleFieldResolverExecutor
import viaduct.remote.fixtures.SimpleNodeResolverExecutor
import viaduct.remote.registry.FieldExecutorRegistry
import viaduct.remote.registry.NodeExecutorRegistry

class RemoteResolverInitializerTest {
    private fun cfg(
        enabled: Boolean = true,
        useStreamingTransport: Boolean = false,
    ) = RemoteResolverConfig(
        enabled = enabled,
        useStreamingTransport = useStreamingTransport,
        rrsHost = "localhost",
        rrsPort = 0,
        // Port 0 lets the OS pick a free callback port.
        callbackPort = 0,
    )

    @Test
    fun `disabled config returns NO_OP`() {
        val initializer =
            RemoteResolverInitializer(
                cfg(enabled = false),
                selection = RemoteResolverSelection(),
            )
        assertSame(ProxyResolverFactory.NO_OP, initializer.initialize())
        initializer.close()
    }

    @Test
    fun `enabled config produces a non-NO_OP factory`() {
        val initializer = RemoteResolverInitializer(cfg(), selection = RemoteResolverSelection())
        try {
            assertTrue(initializer.initialize() !== ProxyResolverFactory.NO_OP)
        } finally {
            initializer.close()
        }
    }

    @Test
    fun `repeat initialize returns the same factory`() {
        val initializer = RemoteResolverInitializer(cfg(), selection = RemoteResolverSelection())
        try {
            assertSame(initializer.initialize(), initializer.initialize())
        } finally {
            initializer.close()
        }
    }

    @Test
    fun `initialize after close throws IllegalStateException`() {
        val initializer = RemoteResolverInitializer(cfg(), selection = RemoteResolverSelection())
        initializer.initialize()
        initializer.close()
        assertThrows<IllegalStateException> { initializer.initialize() }
    }

    @Test
    fun `close before initialize still terminates the instance`() {
        val initializer = RemoteResolverInitializer(cfg(), selection = RemoteResolverSelection())
        initializer.close()
        assertThrows<IllegalStateException> { initializer.initialize() }
    }

    @Test
    fun `explicit selection proxies only listed resolver IDs`() {
        val initializer =
            RemoteResolverInitializer(
                cfg(),
                selection =
                    RemoteResolverSelection(
                        tenantNames = setOf("data/selected"),
                        nodeTypes = setOf("User"),
                        fieldCoordinates = setOf("Character.isAdult", "Query.node"),
                    ),
            )
        try {
            val factory = initializer.initialize()
            assertNotNull(factory.proxyNode(SimpleNodeResolverExecutor.createUserResolver()))
            assertNull(factory.proxyNode(SimpleNodeResolverExecutor.createPostResolver()))
            assertNotNull(factory.proxyField(fieldExecutor("Character.isAdult", "isAdult")))
            assertNotNull(factory.proxyField(fieldExecutor("Query.node", "query-node-resolver")))
            assertNull(factory.proxyField(fieldExecutor("Character.summary", "summary")))
        } finally {
            initializer.close()
            NodeExecutorRegistry.clear()
            FieldExecutorRegistry.clear()
        }
    }

    @Test
    fun `empty explicit selection proxies nothing`() {
        val initializer =
            RemoteResolverInitializer(
                cfg(),
                selection = RemoteResolverSelection(),
            )
        try {
            val factory = initializer.initialize()
            assertNull(factory.proxyNode(SimpleNodeResolverExecutor.createUserResolver()))
            assertNull(factory.proxyField(fieldExecutor("Character.isAdult", "isAdult")))
        } finally {
            initializer.close()
            NodeExecutorRegistry.clear()
            FieldExecutorRegistry.clear()
        }
    }

    @Test
    fun `useStreamingTransport wraps node resolvers with the streaming proxy executor`() {
        val initializer =
            RemoteResolverInitializer(
                cfg(useStreamingTransport = true),
                selection = RemoteResolverSelection(nodeTypes = setOf("User")),
            )
        try {
            val factory = initializer.initialize()
            val node = factory.proxyNode(SimpleNodeResolverExecutor.createUserResolver())
            assertTrue(node is RemoteNodeStreamProxyExecutor, "expected the streaming node proxy executor, got $node")
        } finally {
            initializer.close()
            NodeExecutorRegistry.clear()
        }
    }

    @Test
    fun `default config wraps node resolvers with the unary proxy executor`() {
        val initializer =
            RemoteResolverInitializer(
                cfg(),
                selection = RemoteResolverSelection(nodeTypes = setOf("User")),
            )
        try {
            val factory = initializer.initialize()
            val node = factory.proxyNode(SimpleNodeResolverExecutor.createUserResolver())
            assertTrue(node is UnaryRemoteNodeProxyExecutor, "expected the unary node proxy executor, got $node")
        } finally {
            initializer.close()
            NodeExecutorRegistry.clear()
        }
    }

    // Reuses the shared simple field-resolver fixture; batchResolve is never called by these tests.
    private fun fieldExecutor(
        id: String,
        metadataName: String
    ) = SimpleFieldResolverExecutor(id, ResolverMetadata.forModern(metadataName))
}
