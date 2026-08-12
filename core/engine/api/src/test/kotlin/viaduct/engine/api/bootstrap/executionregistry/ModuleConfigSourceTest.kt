package viaduct.engine.api.bootstrap.executionregistry

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.service.api.spi.InputStreamSource

/**
 * Tests for [ModuleConfigSource] — the `<tenantName, apiName>` identity of a single execution
 * registry configuration input.
 *
 * These assert the contract rather than its current implementation shape: both key fields come from
 * the config JSON itself, the executor factory is deliberately absent from the key, and duplicate
 * keys within one registry-build input are rejected.
 */
class ModuleConfigSourceTest {
    @Test
    fun `from extracts both key fields out of the config JSON`() {
        val source = ModuleConfigSource.from(config(tenantName = "data/todo", apiName = "kotlin"))

        assertEquals("data/todo", source.tenantName)
        assertEquals("kotlin", source.apiName)
    }

    @Test
    fun `from retains the original source object`() {
        val stream = config(tenantName = "data/todo", apiName = "kotlin")

        assertSame(stream, ModuleConfigSource.from(stream).source)
    }

    @Test
    fun `from rejects a config with no tenantName`() {
        val ex = assertThrows<IllegalArgumentException> {
            ModuleConfigSource.from(
                InputStreamSource.fromString(
                    """{"version":"1","executorFactory":"example.Factory","apiName":"kotlin"}""",
                    name = "no-tenant",
                ),
            )
        }
        assertTrue(ex.message!!.contains("must include tenantName"), ex.message)
    }

    @Test
    fun `from rejects a config with no apiName`() {
        val ex = assertThrows<IllegalArgumentException> {
            ModuleConfigSource.from(
                InputStreamSource.fromString(
                    """{"version":"1","executorFactory":"example.Factory","tenantName":"data/todo"}""",
                    name = "no-api",
                ),
            )
        }
        assertTrue(ex.message!!.contains("non-blank apiName"), ex.message)
    }

    @Test
    fun `from rejects a config with a blank apiName`() {
        val ex = assertThrows<IllegalArgumentException> {
            ModuleConfigSource.from(config(tenantName = "data/todo", apiName = "  "))
        }
        assertTrue(ex.message!!.contains("non-blank apiName"), ex.message)
    }

    @Test
    fun `key renders as the documented pair form`() {
        val source = ModuleConfigSource.from(config(tenantName = "data/todo", apiName = "kotlin"))

        assertEquals(ConfigKey("data/todo", "kotlin"), source.key)
        assertEquals("<data/todo, kotlin>", source.key.toString())
    }

    @Test
    fun `an apiName the engine does not declare is still a valid key half`() {
        // apiName is an open string so any non-default tenant API — Airbnb's `classic`, the Java API,
        // or one built outside this engine entirely — owns its own identity. Such a name must work
        // end-to-end here without the engine declaring it; if one ever needs adding beside
        // KOTLIN_API_NAME, this design has been broken.
        val downstream = ModuleConfigSource.from(config(tenantName = "data/todo", apiName = "acme-dsl"))

        assertEquals(ConfigKey("data/todo", "acme-dsl"), downstream.key)
    }

    @Test
    fun `the default api name is a stable wire value`() {
        // Written into every Kotlin tenant config at build time and matched at runtime, so changing
        // this literal breaks every already-generated config.
        assertEquals("kotlin", KOTLIN_API_NAME)
    }

    @Test
    fun `requireUniqueKeys accepts one config per key`() {
        val sources = listOf(
            ModuleConfigSource.from(config(tenantName = "data/todo", apiName = "kotlin")),
            ModuleConfigSource.from(config(tenantName = "data/todo", apiName = "other")),
            ModuleConfigSource.from(config(tenantName = "data/other", apiName = "kotlin")),
        )

        assertEquals(sources, ModuleConfigSource.requireUniqueKeys(sources))
    }

    @Test
    fun `requireUniqueKeys rejects two configs claiming the same key`() {
        val sources = listOf(
            ModuleConfigSource.from(config(tenantName = "data/todo", apiName = "kotlin", name = "first")),
            ModuleConfigSource.from(config(tenantName = "data/todo", apiName = "kotlin", name = "second")),
        )

        val ex = assertThrows<IllegalArgumentException> {
            ModuleConfigSource.requireUniqueKeys(sources)
        }
        assertTrue(ex.message!!.contains("Duplicate execution registry config sources"), ex.message)
        assertTrue(ex.message!!.contains("<data/todo, kotlin>"), ex.message)
    }

    @Test
    fun `requireUniqueKeys rejects duplicates that differ only in executor factory`() {
        // Two sources for one key are malformed inputs even when their factories disagree: only one
        // config can occupy the slot, and picking by list order would make registration depend on
        // discovery order.
        val sources = listOf(
            ModuleConfigSource.from(
                config(tenantName = "data/todo", apiName = "kotlin", executorFactory = "example.A", name = "a"),
            ),
            ModuleConfigSource.from(
                config(tenantName = "data/todo", apiName = "kotlin", executorFactory = "example.B", name = "b"),
            ),
        )

        assertThrows<IllegalArgumentException> {
            ModuleConfigSource.requireUniqueKeys(sources)
        }
    }

    private fun config(
        tenantName: String,
        apiName: String,
        executorFactory: String = "example.Factory",
        name: String = "$tenantName.$apiName",
    ): InputStreamSource =
        InputStreamSource.fromString(
            """
            {
              "version": "1",
              "executorFactory": "$executorFactory",
              "tenantName": "$tenantName",
              "apiName": "$apiName"
            }
            """.trimIndent(),
            name = name,
        )
}
