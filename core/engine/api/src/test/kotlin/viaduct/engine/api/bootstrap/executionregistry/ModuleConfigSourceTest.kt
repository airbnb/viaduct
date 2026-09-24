package viaduct.engine.api.bootstrap.executionregistry

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.bootstrap.ConfigKey
import viaduct.bootstrap.ExecutionRegistryConfigFile
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
                    """{"version":"${ExecutionRegistryConfigFile.CURRENT_VERSION}","executorFactory":"example.Factory","apiName":"kotlin"}""",
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
                    """{"version":"${ExecutionRegistryConfigFile.CURRENT_VERSION}","executorFactory":"example.Factory","tenantName":"data/todo"}""",
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

    @Test
    fun `config contents remain stable until a new source snapshot is loaded`() {
        var contents = config("data/todo", "kotlin", "example.OldFactory")
        val stream = InputStreamSource { contents.openStream() }
        val original = ModuleConfigSource.from(stream)

        contents = config("data/other", "kotlin", "example.NewFactory")
        val replacement = ModuleConfigSource.from(stream)

        assertEquals("data/todo", original.config.tenantName)
        assertEquals("example.OldFactory", original.config.executorFactory)
        assertEquals("data/other", replacement.config.tenantName)
        assertEquals("example.NewFactory", replacement.config.executorFactory)
    }

    @Test
    fun `shared config snapshots reject mutations of nested collections`() {
        val snapshot = ModuleConfigSource.from(
            InputStreamSource.fromString(
                """
            {
              "version": "1",
              "tenantName": "data/todo",
              "apiName": "kotlin",
              "executorFactory": "example.Factory",
              "namedFragments": ["fragment Foo on Query { __typename }"],
              "nodes": [{
                "typeName": "Todo", "isBatching": false, "isSelective": false,
                "attribution": "test", "tenantAPIData": {"nested": [{"value": "original"}]}
              }],
              "fields": [{
                "typeName": "Todo", "fieldName": "title", "isBatching": false, "isSelective": false,
                "attribution": "test", "tenantAPIData": {"resolver": "example.Resolver"},
                "objectSelections": {
                  "selections": "fragment _ on Todo { id }",
                  "variablesProviders": [{
                    "providedVariables": {"id": "ID"},
                    "providerVariablesAPIData": {"type": "fromObjectField", "path": "id"}
                  }]
                },
                "querySelections": {"selections": "fragment _ on Query { __typename }"}
              }]
            }
                """.trimIndent(),
                name = "nested-config",
            )
        ).config
        val node = snapshot.nodes.single()
        val field = snapshot.fields.single()
        val nested = node.tenantAPIData.getValue("nested") as List<*>
        val nestedMap = nested.single() as Map<*, *>
        val variables = requireNotNull(field.objectSelections).variablesProviders

        assertEquals("original", nestedMap["value"])
        assertThrows<UnsupportedOperationException> { (snapshot.nodes as MutableList<*>).clear() }
        assertThrows<UnsupportedOperationException> { (snapshot.fields as MutableList<*>).clear() }
        assertThrows<UnsupportedOperationException> { (snapshot.namedFragments as MutableList<*>).clear() }
        assertThrows<UnsupportedOperationException> { (node.tenantAPIData as MutableMap<*, *>).clear() }
        assertThrows<UnsupportedOperationException> { (field.tenantAPIData as MutableMap<*, *>).clear() }
        assertThrows<UnsupportedOperationException> { (nested as MutableList<*>).clear() }
        assertThrows<UnsupportedOperationException> { (nestedMap as MutableMap<*, *>).clear() }
        assertThrows<UnsupportedOperationException> { (variables as MutableList<*>).clear() }
        assertThrows<UnsupportedOperationException> { (variables.single().providedVariables as MutableMap<*, *>).clear() }
        assertThrows<UnsupportedOperationException> {
            (requireNotNull(field.querySelections).variablesProviders as MutableList<*>).clear()
        }
    }

    @Test
    fun `validated config collection is independent of the caller list`() {
        val original = ModuleConfigSource.from(config("data/todo", "kotlin"))
        val inputs = mutableListOf(original)
        val snapshot = ModuleConfigSource.requireUniqueKeys(inputs)

        inputs.clear()

        assertEquals(listOf(original), snapshot)
        assertThrows<UnsupportedOperationException> { (snapshot as MutableList<*>).clear() }
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
              "version": "${ExecutionRegistryConfigFile.CURRENT_VERSION}",
              "executorFactory": "$executorFactory",
              "tenantName": "$tenantName",
              "apiName": "$apiName"
            }
            """.trimIndent(),
            name = name,
        )
}
