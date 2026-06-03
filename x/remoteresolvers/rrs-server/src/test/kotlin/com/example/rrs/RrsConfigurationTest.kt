package com.example.rrs

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import viaduct.remote.config.EnvLookup

class RrsConfigurationTest {
    private fun envOf(vararg pairs: Pair<String, String?>): EnvLookup {
        val map = pairs.toMap()
        return EnvLookup { name -> map[name] }
    }

    @Test
    fun `defaults when env is empty`() {
        val cfg = RrsConfiguration.fromEnvironment(envOf())
        assertThat(cfg.port).isEqualTo(RrsConfiguration.DEFAULT_PORT)
        assertThat(cfg.callbackHost).isEqualTo(RrsConfiguration.DEFAULT_CALLBACK_HOST)
        assertThat(cfg.callbackPort).isEqualTo(RrsConfiguration.DEFAULT_CALLBACK_PORT)
    }

    @Test
    fun `reads each value from env`() {
        val cfg = RrsConfiguration.fromEnvironment(
            envOf(
                RrsConfiguration.ENV_PORT to "60001",
                RrsConfiguration.ENV_CALLBACK_HOST to "callback.example",
                RrsConfiguration.ENV_CALLBACK_PORT to "60002",
            )
        )
        assertThat(cfg.port).isEqualTo(60001)
        assertThat(cfg.callbackHost).isEqualTo("callback.example")
        assertThat(cfg.callbackPort).isEqualTo(60002)
    }

    @Test
    fun `unparseable port falls back to default`() {
        val cfg = RrsConfiguration.fromEnvironment(envOf(RrsConfiguration.ENV_PORT to "not-a-port"))
        assertThat(cfg.port).isEqualTo(RrsConfiguration.DEFAULT_PORT)
    }

    @Test
    fun `args override env`() {
        val env = envOf(
            RrsConfiguration.ENV_PORT to "60001",
            RrsConfiguration.ENV_CALLBACK_HOST to "callback.example",
            RrsConfiguration.ENV_CALLBACK_PORT to "60002",
        )
        val cfg = RrsConfiguration.fromArgs(
            arrayOf("--port", "70001", "--callback-host", "override.example", "--callback-port", "70002"),
            env,
        )
        assertThat(cfg.port).isEqualTo(70001)
        assertThat(cfg.callbackHost).isEqualTo("override.example")
        assertThat(cfg.callbackPort).isEqualTo(70002)
    }

    @Test
    fun `args inherit unspecified values from env`() {
        val env = envOf(RrsConfiguration.ENV_CALLBACK_HOST to "callback.example")
        val cfg = RrsConfiguration.fromArgs(arrayOf("--port", "70001"), env)
        assertThat(cfg.port).isEqualTo(70001)
        assertThat(cfg.callbackHost).isEqualTo("callback.example")
        assertThat(cfg.callbackPort).isEqualTo(RrsConfiguration.DEFAULT_CALLBACK_PORT)
    }

    @Test
    fun `unknown args are skipped`() {
        val cfg = RrsConfiguration.fromArgs(arrayOf("--port", "70001", "--bogus", "value"), envOf())
        assertThat(cfg.port).isEqualTo(70001)
    }

    @Test
    fun `unparseable arg port falls back to env or default`() {
        val cfg = RrsConfiguration.fromArgs(arrayOf("--port", "not-a-port"), envOf())
        assertThat(cfg.port).isEqualTo(RrsConfiguration.DEFAULT_PORT)
    }
}
