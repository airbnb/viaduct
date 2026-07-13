@file:Suppress("ForbiddenImport")

package viaduct.remote

import io.grpc.inprocess.InProcessChannelBuilder
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.remote.fixtures.SimpleFieldResolverExecutor
import viaduct.remote.fixtures.SimpleNodeResolverExecutor
import viaduct.remote.registry.FieldExecutorRegistry
import viaduct.remote.registry.NodeExecutorRegistry

/**
 * Covers [RemoteProxyResolverFactory] field-proxying wiring: the default factory proxies every field
 * resolver (mirroring nodes), while [RemoteProxyResolverFactory.proxyFields] narrows to only the
 * listed field coordinates ("Type.field"), leaving every other field to run locally (returns null).
 */
class RemoteFieldProxyFactoryTest {
    @Test
    fun `default factory proxies every field resolver`() {
        val rrsChannel = InProcessChannelBuilder.forName("test-rrs-default-${System.nanoTime()}").directExecutor().build()
        try {
            // The bare factory (no predicates) proxies all field resolvers by default, like nodes.
            val factory = RemoteProxyResolverFactory(rrsChannel, "test-cb")
            val proxied = factory.proxyField(SimpleFieldResolverExecutor(resolverId = "Character.isAdult"))
            assertTrue(proxied is RemoteFieldProxyExecutor, "Default factory should proxy every field resolver")
        } finally {
            rrsChannel.shutdownNow()
            FieldExecutorRegistry.clear()
        }
    }

    @Test
    fun `a selective field resolver is never proxied by the default factory`() {
        val rrsChannel = InProcessChannelBuilder.forName("test-rrs-selective-${System.nanoTime()}").directExecutor().build()
        try {
            // Selective resolvers can't round-trip over the wire, so the default "proxy all" factory
            // must skip them (return null) rather than crash bootstrap constructing a proxy for one.
            val factory = RemoteProxyResolverFactory(rrsChannel, "test-cb")
            val selective = object : FieldResolverExecutor by SimpleFieldResolverExecutor(resolverId = "Character.selective") {
                override val isSelective: Boolean = true
            }
            assertNull(factory.proxyField(selective), "a selective field resolver must not be proxied")
        } finally {
            rrsChannel.shutdownNow()
            FieldExecutorRegistry.clear()
        }
    }

    @Test
    fun `a selective node resolver is never proxied by the default factory`() {
        val rrsChannel = InProcessChannelBuilder.forName("test-rrs-node-selective-${System.nanoTime()}").directExecutor().build()
        try {
            // Mirrors the selective-field case for nodes: selective resolvers can't round-trip over the
            // wire, so even the "proxy all" node factory must skip them (return null) rather than crash
            // bootstrap constructing a proxy for one.
            val factory = RemoteProxyResolverFactory.proxyAll(rrsChannel, "test-cb")
            val selective = object : NodeResolverExecutor by SimpleNodeResolverExecutor(
                typeName = "Character",
                nodeData = emptyMap()
            ) {
                override val isSelective: Boolean = true
            }
            assertNull(factory.proxyNode(selective), "a selective node resolver must not be proxied")
        } finally {
            rrsChannel.shutdownNow()
            NodeExecutorRegistry.clear()
        }
    }

    @Test
    fun `proxyFields factory wraps only the listed field coordinates`() {
        val rrsChannel = InProcessChannelBuilder.forName("test-rrs-proxyfields-${System.nanoTime()}").directExecutor().build()
        try {
            // Only "Character.isAdult" is listed; the predicate keys solely on resolverId, so the
            // same fixture with the unlisted "Character.summary" id is left unproxied (returns null).
            val factory = RemoteProxyResolverFactory.proxyFields(rrsChannel, "test-cb", "Character.isAdult")

            val listed = factory.proxyField(SimpleFieldResolverExecutor(resolverId = "Character.isAdult"))
            val unlisted = factory.proxyField(SimpleFieldResolverExecutor(resolverId = "Character.summary"))

            assertTrue(listed is RemoteFieldProxyExecutor, "Listed coordinate should be proxied as a RemoteFieldProxyExecutor")
            assertNull(unlisted, "Unlisted coordinate should not be proxied")
        } finally {
            rrsChannel.shutdownNow()
            FieldExecutorRegistry.clear()
        }
    }
}
