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
    fun `defaults to disabled when env is empty`() {
        val cfg = RemoteResolverConfig.fromEnvironment(envOf())
        assertFalse(cfg.enabled)
        assertEquals(emptySet<String>(), cfg.remoteTypes)
    }

    @Test
    fun `enabled only when value is exactly 'true'`() {
        assertTrue(RemoteResolverConfig.fromEnvironment(envOf(RemoteResolverConfig.ENV_ENABLED to "true")).enabled)
        assertFalse(RemoteResolverConfig.fromEnvironment(envOf(RemoteResolverConfig.ENV_ENABLED to "false")).enabled)
    }

    @Test
    fun `non-strict-boolean values stay disabled`() {
        // toBooleanStrictOrNull rejects "1" / "yes" / blank / garbage; default is false.
        for (raw in listOf("1", "0", "yes", "no", "TRUE", "True", "", " ", "maybe")) {
            val cfg = RemoteResolverConfig.fromEnvironment(envOf(RemoteResolverConfig.ENV_ENABLED to raw))
            assertFalse(cfg.enabled, "value=$raw should leave proxy disabled")
        }
    }

    @Test
    fun `types are split on comma and trimmed`() {
        val cfg = RemoteResolverConfig.fromEnvironment(envOf(RemoteResolverConfig.ENV_TYPES to "Film, Character ,Planet"))
        assertEquals(setOf("Film", "Character", "Planet"), cfg.remoteTypes)
    }

    @Test
    fun `empty entries in types csv are dropped`() {
        val cfg = RemoteResolverConfig.fromEnvironment(envOf(RemoteResolverConfig.ENV_TYPES to ",Film,, ,Character,"))
        assertEquals(setOf("Film", "Character"), cfg.remoteTypes)
    }

    @Test
    fun `types defaults to empty set when env is unset`() {
        val cfg = RemoteResolverConfig.fromEnvironment(envOf())
        assertEquals(emptySet<String>(), cfg.remoteTypes)
    }

    @Test
    fun `sentinel fields value disables field proxying and clears coordinates`() {
        // `none` / `off` / `-` (case-insensitive, trimmed) are the field-only off switch: they turn
        // field proxying off and clear remoteFields, distinct from unset/empty (= proxy all fields).
        for (raw in listOf("none", "off", "-", " NONE ")) {
            val cfg = RemoteResolverConfig.fromEnvironment(envOf(RemoteResolverConfig.ENV_FIELDS to raw))
            assertFalse(cfg.fieldProxyingEnabled, "value=$raw should disable field proxying")
            assertEquals(emptySet<String>(), cfg.remoteFields, "value=$raw should clear remoteFields")
        }
    }

    @Test
    fun `non-sentinel fields value parses coordinates with proxying enabled`() {
        val cfg = RemoteResolverConfig.fromEnvironment(
            envOf(RemoteResolverConfig.ENV_FIELDS to "Character.isAdult,Character.summary")
        )
        assertTrue(cfg.fieldProxyingEnabled)
        assertEquals(setOf("Character.isAdult", "Character.summary"), cfg.remoteFields)
    }

    @Test
    fun `field proxying stays enabled with empty coordinates when fields env is unset or blank`() {
        // Neither unset nor blank is a sentinel: proxying stays on and the empty set means "all fields".
        val unset = RemoteResolverConfig.fromEnvironment(envOf())
        assertTrue(unset.fieldProxyingEnabled)
        assertEquals(emptySet<String>(), unset.remoteFields)

        val blank = RemoteResolverConfig.fromEnvironment(envOf(RemoteResolverConfig.ENV_FIELDS to ""))
        assertTrue(blank.fieldProxyingEnabled)
        assertEquals(emptySet<String>(), blank.remoteFields)
    }

    @Test
    fun `direct construction does not read env`() {
        // Constructor must not depend on EnvLookup — used by tests/hosts to build
        // configs explicitly without env-var coupling.
        val cfg = RemoteResolverConfig(enabled = true, remoteTypes = setOf("Film"))
        assertTrue(cfg.enabled)
        assertEquals(setOf("Film"), cfg.remoteTypes)
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
