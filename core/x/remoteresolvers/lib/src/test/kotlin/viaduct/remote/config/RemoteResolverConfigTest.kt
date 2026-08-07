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
        assertEquals(RemoteResolverConfig.DEFAULT_RRS_HOST, defaults.rrsHost)
        assertEquals(RemoteResolverConfig.DEFAULT_RRS_PORT, defaults.rrsPort)
        assertEquals(RemoteResolverConfig.DEFAULT_CALLBACK_PORT, defaults.callbackPort)

        val overridden = RemoteResolverConfig.fromEnvironment(
            envOf(
                RemoteResolverConfig.ENV_RRS_HOST to "rrs.internal",
                RemoteResolverConfig.ENV_RRS_PORT to "60001",
                RemoteResolverConfig.ENV_CALLBACK_PORT to "60002",
            )
        )
        assertEquals("rrs.internal", overridden.rrsHost)
        assertEquals(60001, overridden.rrsPort)
        assertEquals(60002, overridden.callbackPort)
    }

    @Test
    fun `unparseable port values fall back to defaults`() {
        val cfg = RemoteResolverConfig.fromEnvironment(
            envOf(
                RemoteResolverConfig.ENV_RRS_PORT to "not-a-port",
                RemoteResolverConfig.ENV_CALLBACK_PORT to "also-not",
            )
        )
        assertEquals(RemoteResolverConfig.DEFAULT_RRS_PORT, cfg.rrsPort)
        assertEquals(RemoteResolverConfig.DEFAULT_CALLBACK_PORT, cfg.callbackPort)
    }
}
