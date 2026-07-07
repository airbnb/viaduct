package com.example.remote

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import viaduct.remote.config.EnvLookup

class RemoteConfigurationTest {
    private fun envOf(vararg pairs: Pair<String, String?>): EnvLookup {
        val map = pairs.toMap()
        return EnvLookup { name -> map[name] }
    }

    @Test
    fun `defaults when env is empty`() {
        val cfg = RemoteConfiguration.fromEnvironment(envOf())
        assertEquals(RemoteConfiguration.DEFAULT_PORT, cfg.port)
        assertEquals(RemoteConfiguration.DEFAULT_CALLBACK_HOST, cfg.callbackHost)
        assertEquals(RemoteConfiguration.DEFAULT_CALLBACK_PORT, cfg.callbackPort)
    }

    @Test
    fun `reads each value from env`() {
        val cfg = RemoteConfiguration.fromEnvironment(
            envOf(
                RemoteConfiguration.ENV_PORT to "60001",
                RemoteConfiguration.ENV_CALLBACK_HOST to "callback.example",
                RemoteConfiguration.ENV_CALLBACK_PORT to "60002",
            )
        )
        assertEquals(60001, cfg.port)
        assertEquals("callback.example", cfg.callbackHost)
        assertEquals(60002, cfg.callbackPort)
    }

    @Test
    fun `unparseable port falls back to default`() {
        val cfg = RemoteConfiguration.fromEnvironment(envOf(RemoteConfiguration.ENV_PORT to "not-a-port"))
        assertEquals(RemoteConfiguration.DEFAULT_PORT, cfg.port)
    }

    @Test
    fun `args override env`() {
        val env = envOf(
            RemoteConfiguration.ENV_PORT to "60001",
            RemoteConfiguration.ENV_CALLBACK_HOST to "callback.example",
            RemoteConfiguration.ENV_CALLBACK_PORT to "60002",
        )
        val cfg = RemoteConfiguration.fromArgs(
            arrayOf("--port", "70001", "--callback-host", "override.example", "--callback-port", "70002"),
            env,
        )
        assertEquals(70001, cfg.port)
        assertEquals("override.example", cfg.callbackHost)
        assertEquals(70002, cfg.callbackPort)
    }

    @Test
    fun `args inherit unspecified values from env`() {
        val env = envOf(RemoteConfiguration.ENV_CALLBACK_HOST to "callback.example")
        val cfg = RemoteConfiguration.fromArgs(arrayOf("--port", "70001"), env)
        assertEquals(70001, cfg.port)
        assertEquals("callback.example", cfg.callbackHost)
        assertEquals(RemoteConfiguration.DEFAULT_CALLBACK_PORT, cfg.callbackPort)
    }

    @Test
    fun `unknown args are skipped`() {
        val cfg = RemoteConfiguration.fromArgs(arrayOf("--port", "70001", "--bogus", "value"), envOf())
        assertEquals(70001, cfg.port)
    }

    @Test
    fun `unparseable arg port falls back to env or default`() {
        val cfg = RemoteConfiguration.fromArgs(arrayOf("--port", "not-a-port"), envOf())
        assertEquals(RemoteConfiguration.DEFAULT_PORT, cfg.port)
    }
}
