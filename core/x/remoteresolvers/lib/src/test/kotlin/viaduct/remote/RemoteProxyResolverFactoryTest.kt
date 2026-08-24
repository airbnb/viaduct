@file:Suppress("ForbiddenImport")

package viaduct.remote

import io.grpc.inprocess.InProcessChannelBuilder
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.remote.fixtures.SimpleFieldResolverExecutor
import viaduct.remote.fixtures.SimpleNodeResolverExecutor
import viaduct.remote.registry.FieldExecutorRegistry
import viaduct.remote.registry.NodeExecutorRegistry

/**
 * Covers [RemoteProxyResolverFactory]'s config-gated cutover for node resolvers:
 * [RemoteProxyResolverFactory.useStreamingTransport] selects which node proxy executor class
 * wraps a resolver. Field resolvers are unaffected -- they always get the unary proxy executor,
 * since streaming doesn't support fields yet.
 */
class RemoteProxyResolverFactoryTest {
    @Test
    fun `default factory proxies nodes with the unary executor`() {
        val rrsChannel = InProcessChannelBuilder.forName("test-rrs-unary-${System.nanoTime()}").directExecutor().build()
        try {
            val factory = RemoteProxyResolverFactory(rrsChannel, "test-cb")
            val node = factory.proxyNode(SimpleNodeResolverExecutor.createUserResolver())
            assertTrue(node is UnaryRemoteNodeProxyExecutor, "expected the unary node proxy executor")
        } finally {
            rrsChannel.shutdownNow()
            NodeExecutorRegistry.clear()
        }
    }

    @Test
    fun `useStreamingTransport proxies nodes with the streaming executor`() {
        val rrsChannel = InProcessChannelBuilder.forName("test-rrs-streaming-${System.nanoTime()}").directExecutor().build()
        try {
            val factory = RemoteProxyResolverFactory(rrsChannel, "test-cb", useStreamingTransport = true)
            val node = factory.proxyNode(SimpleNodeResolverExecutor.createUserResolver())
            assertTrue(node is RemoteNodeStreamProxyExecutor, "expected the streaming node proxy executor")
        } finally {
            rrsChannel.shutdownNow()
            NodeExecutorRegistry.clear()
        }
    }

    @Test
    fun `a proxied node resolver's metadata is tagged isRemote, regardless of transport`() {
        val rrsChannel = InProcessChannelBuilder.forName("test-rrs-node-metadata-${System.nanoTime()}").directExecutor().build()
        try {
            val original = SimpleNodeResolverExecutor.createUserResolver()
            val unaryFactory = RemoteProxyResolverFactory(rrsChannel, "test-cb")
            val streamingFactory = RemoteProxyResolverFactory(rrsChannel, "test-cb", useStreamingTransport = true)

            assertTrue(original.metadata.isRemote == false, "the original executor's metadata should be unaffected")
            assertTrue(
                unaryFactory.proxyNode(original)?.metadata?.isRemote == true,
                "the unary proxy's metadata should be tagged isRemote"
            )
            assertTrue(
                streamingFactory.proxyNode(original)?.metadata?.isRemote == true,
                "the streaming proxy's metadata should be tagged isRemote"
            )
        } finally {
            rrsChannel.shutdownNow()
            NodeExecutorRegistry.clear()
        }
    }

    @Test
    fun `useStreamingTransport does not affect field resolvers -- they stay on the unary executor`() {
        val rrsChannel = InProcessChannelBuilder.forName("test-rrs-streaming-field-${System.nanoTime()}").directExecutor().build()
        try {
            val factory = RemoteProxyResolverFactory(rrsChannel, "test-cb", useStreamingTransport = true)
            val field = factory.proxyField(SimpleFieldResolverExecutor(resolverId = "Character.isAdult"))
            assertTrue(field is RemoteFieldProxyExecutor, "expected the unary field proxy executor even with useStreamingTransport")
        } finally {
            rrsChannel.shutdownNow()
            FieldExecutorRegistry.clear()
        }
    }
}
