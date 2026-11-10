package viaduct.remote.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RemoteResolverConfigTest {
    private fun envOf(vararg pairs: Pair<String, String?>): EnvLookup {
        val map = pairs.toMap()
        return EnvLookup { name -> map[name] }
    }

    @Test
    fun `enabled defaults to false and accepts an explicit value`() {
        assertFalse(RemoteResolverConfig.fromEnvironment(envOf()).enabled)
        assertTrue(RemoteResolverConfig.fromEnvironment(envOf(), enabled = true).enabled)
    }

    @Test
    fun `useStreamingTransport defaults to false and accepts an explicit value`() {
        assertFalse(RemoteResolverConfig.fromEnvironment(envOf()).useStreamingTransport)
        assertTrue(RemoteResolverConfig.fromEnvironment(envOf(), useStreamingTransport = true).useStreamingTransport)
    }

    @Test
    fun `network endpoints default and override`() {
        val defaults = RemoteResolverConfig.fromEnvironment(envOf())
        assertEquals(RemoteResolverConfig.DEFAULT_RTS_HOST, defaults.rtsHost)
        assertEquals(RemoteResolverConfig.DEFAULT_RTS_PORT, defaults.rtsPort)
        assertEquals(RemoteResolverConfig.DEFAULT_CALLBACK_PORT, defaults.callbackPort)

        val overridden = RemoteResolverConfig.fromEnvironment(
            envOf(
                "VIADUCT_RTS_HOST" to "rts.internal",
                "VIADUCT_RTS_PORT" to "60001",
                "VIADUCT_RTS_CALLBACK_PORT" to "60002",
            )
        )
        assertEquals("rts.internal", overridden.rtsHost)
        assertEquals(60001, overridden.rtsPort)
        assertEquals(60002, overridden.callbackPort)
    }

    @Test
    fun `unparseable port values fall back to defaults`() {
        val cfg = RemoteResolverConfig.fromEnvironment(
            envOf(
                RemoteResolverConfig.ENV_RTS_PORT to "not-a-port",
                RemoteResolverConfig.ENV_CALLBACK_PORT to "also-not",
            )
        )
        assertEquals(RemoteResolverConfig.DEFAULT_RTS_PORT, cfg.rtsPort)
        assertEquals(RemoteResolverConfig.DEFAULT_CALLBACK_PORT, cfg.callbackPort)
    }
}
