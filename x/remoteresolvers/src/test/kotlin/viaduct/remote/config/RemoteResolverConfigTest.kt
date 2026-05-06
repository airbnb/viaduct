package viaduct.remote.config

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
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
        assertEquals(emptySet(), cfg.remoteTypes)
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
        assertEquals(emptySet(), cfg.remoteTypes)
    }

    @Test
    fun `direct construction does not read env`() {
        // Constructor must not depend on EnvLookup — used by tests/hosts to build
        // configs explicitly without env-var coupling.
        val cfg = RemoteResolverConfig(enabled = true, remoteTypes = setOf("Film"))
        assertTrue(cfg.enabled)
        assertEquals(setOf("Film"), cfg.remoteTypes)
    }
}
