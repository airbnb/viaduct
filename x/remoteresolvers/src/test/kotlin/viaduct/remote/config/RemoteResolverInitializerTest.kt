package viaduct.remote.config

import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.api.spi.ProxyResolverFactory

class RemoteResolverInitializerTest {
    private fun cfg(
        enabled: Boolean,
        rrsServerName: String = "rrs-${System.nanoTime()}",
        callbackEndpoint: String = "rrp-${System.nanoTime()}",
    ) = RemoteResolverConfig(
        enabled = enabled,
        remoteTypes = emptySet(),
        rrsServerName = rrsServerName,
        callbackEndpoint = callbackEndpoint,
    )

    @Test
    fun `disabled config returns NO_OP`() {
        val initializer = RemoteResolverInitializer(cfg(enabled = false))
        assertSame(ProxyResolverFactory.NO_OP, initializer.initialize())
        initializer.close()
    }

    @Test
    fun `enabled config produces a non-NO_OP factory`() {
        val initializer = RemoteResolverInitializer(cfg(enabled = true))
        try {
            assertTrue(initializer.initialize() !== ProxyResolverFactory.NO_OP)
        } finally {
            initializer.close()
        }
    }

    @Test
    fun `repeat initialize returns the same factory`() {
        val initializer = RemoteResolverInitializer(cfg(enabled = true))
        try {
            assertSame(initializer.initialize(), initializer.initialize())
        } finally {
            initializer.close()
        }
    }

    @Test
    fun `initialize after close throws IllegalStateException`() {
        val initializer = RemoteResolverInitializer(cfg(enabled = true))
        initializer.initialize()
        initializer.close()
        assertFailsWith<IllegalStateException> { initializer.initialize() }
    }

    @Test
    fun `close before initialize still terminates the instance`() {
        val initializer = RemoteResolverInitializer(cfg(enabled = true))
        initializer.close()
        assertFailsWith<IllegalStateException> { initializer.initialize() }
    }
}
