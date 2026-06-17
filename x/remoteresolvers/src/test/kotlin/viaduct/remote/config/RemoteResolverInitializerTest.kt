package viaduct.remote.config

import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
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

    private fun networkCfg() =
        RemoteResolverConfig(
            enabled = true,
            mode = RemoteResolverMode.NETWORK,
            remoteTypes = emptySet(),
            // Port 0 lets the OS pick a free callback port.
            rrsHost = "localhost",
            rrsPort = 0,
            callbackPort = 0,
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
        assertThrows<IllegalStateException> { initializer.initialize() }
    }

    @Test
    fun `close before initialize still terminates the instance`() {
        val initializer = RemoteResolverInitializer(cfg(enabled = true))
        initializer.close()
        assertThrows<IllegalStateException> { initializer.initialize() }
    }

    @Test
    fun `network mode produces a non-NO_OP factory and binds the callback port`() {
        val initializer = RemoteResolverInitializer(networkCfg())
        try {
            assertTrue(initializer.initialize() !== ProxyResolverFactory.NO_OP)
        } finally {
            initializer.close()
        }
    }

    @Test
    fun `network mode initialize is idempotent`() {
        val initializer = RemoteResolverInitializer(networkCfg())
        try {
            assertSame(initializer.initialize(), initializer.initialize())
        } finally {
            initializer.close()
        }
    }

    @Test
    fun `network mode initialize after close throws IllegalStateException`() {
        val initializer = RemoteResolverInitializer(networkCfg())
        initializer.initialize()
        initializer.close()
        assertThrows<IllegalStateException> { initializer.initialize() }
    }
}
