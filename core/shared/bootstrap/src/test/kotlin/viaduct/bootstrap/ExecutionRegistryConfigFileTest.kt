package viaduct.bootstrap

import io.kotest.matchers.string.shouldContain
import java.io.ByteArrayInputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Tests for the bootstrap wire format.
 *
 * The point of this module is that a producer outside the engine can build one of these files and
 * have the engine read it back, so these assert the wire contract itself: that a fully populated
 * config survives a round trip, that the JSON field names are what a producer has to write, and that
 * unknown fields remain compatible while unsupported wire-format versions are rejected.
 */
class ExecutionRegistryConfigFileTest {
    @Test
    fun `new configs default to the current version`() {
        val config = ExecutionRegistryConfigFile(executorFactory = "example.Factory")

        assertEquals(ExecutionRegistryConfigFile.CURRENT_VERSION, config.version)
    }

    @Test
    fun `parse defaults a missing version to the current version`() {
        val config = parse("""{"executorFactory":"example.Factory"}""")

        assertEquals(ExecutionRegistryConfigFile.CURRENT_VERSION, config.version)
    }

    @Test
    fun `parse accepts the current version`() {
        val config = parse(
            """{"version":"${ExecutionRegistryConfigFile.CURRENT_VERSION}","executorFactory":"example.Factory"}""",
        )

        assertEquals(ExecutionRegistryConfigFile.CURRENT_VERSION, config.version)
    }

    @Test
    fun `parse rejects a version other than current`() {
        val ex = assertThrows<IllegalArgumentException> {
            parse("""{"version":"999","executorFactory":"example.Factory"}""")
        }
        ex.message!! shouldContain "version '999'"
        ex.message!! shouldContain "expected '${ExecutionRegistryConfigFile.CURRENT_VERSION}'"
    }

    @Test
    fun `a fully populated config survives a round trip`() {
        val parsed = parse(ExecutionRegistryConfigFile.toJson(fullConfig))

        assertEquals(fullConfig, parsed)
        assertEquals(fullConfig.hashCode(), parsed.hashCode())
    }

    @Test
    fun `the boolean entry flags keep their as-written JSON names`() {
        // Jackson would otherwise strip the `is` prefix, silently renaming fields every producer and
        // every already-generated config file spells `isBatching`/`isSelective`.
        val json = ExecutionRegistryConfigFile.toJson(fullConfig)

        json shouldContain "\"isBatching\""
        json shouldContain "\"isSelective\""
        assertFalse(json.contains("\"batching\""), json)
        assertFalse(json.contains("\"selective\""), json)
    }

    @Test
    fun `optional fields fall back to their defaults`() {
        val parsed = parse("""{"version":"${ExecutionRegistryConfigFile.CURRENT_VERSION}","executorFactory":"example.Factory"}""")

        assertNull(parsed.tenantName)
        assertNull(parsed.apiName)
        assertNull(parsed.bootstrapClass)
        assertTrue(parsed.nodes.isEmpty())
        assertTrue(parsed.fields.isEmpty())
        assertTrue(parsed.namedFragments.isEmpty())
    }

    @Test
    fun `a config carrying fields this version does not know still parses`() {
        // Unknown fields remain compatible within the current wire-format version.
        val parsed = parse(
            """{"version":"${ExecutionRegistryConfigFile.CURRENT_VERSION}","executorFactory":"example.Factory","somethingNewer":{"a":1}}""",
        )

        assertEquals("example.Factory", parsed.executorFactory)
    }

    @Test
    fun `the executor factory is not part of a config's identity`() {
        // Two configs for one <tenantName, apiName> are the same slot even with different factories;
        // ConfigKey exists so that distinction is expressible.
        val other = fullConfig.copy(executorFactory = "example.OtherFactory")

        assertNotEquals(fullConfig, other)
        assertEquals(
            ConfigKey(fullConfig.tenantName!!, fullConfig.apiName!!),
            ConfigKey(other.tenantName!!, other.apiName!!),
        )
    }

    @Test
    fun `the default api name is a stable wire value`() {
        // Written into every Kotlin tenant config at build time and matched at runtime, so changing
        // this literal breaks every already-generated config.
        assertEquals("kotlin", KOTLIN_API_NAME)
    }

    @Test
    fun `a config key renders as the documented pair form`() {
        assertEquals("<data/todo, kotlin>", ConfigKey("data/todo", KOTLIN_API_NAME).toString())
    }

    @Test
    fun `a config renders its own contents when diagnostics print it`() {
        fullConfig.toString() shouldContain "data/todo"
    }

    private fun parse(json: String): ExecutionRegistryConfigFile {
        return ByteArrayInputStream(json.toByteArray()).use { ExecutionRegistryConfigFile.parse(it) }
    }

    private val fullConfig = ExecutionRegistryConfigFile(
        executorFactory = "example.Factory",
        tenantName = "data/todo",
        apiName = KOTLIN_API_NAME,
        nodes = listOf(
            NodeEntryConfig(
                typeName = "Todo",
                isBatching = true,
                isSelective = false,
                attribution = "example.TodoNodeResolver",
                tenantAPIData = mapOf("resolverClass" to "example.TodoNodeResolver"),
            ),
        ),
        fields = listOf(
            FieldEntryConfig(
                typeName = "Todo",
                fieldName = "title",
                isBatching = false,
                isSelective = true,
                attribution = "example.TitleResolver",
                objectSelections = SelectionsBlockConfig(
                    selections = "fragment _ on Todo { id }",
                    variablesProviders = listOf(
                        VariableProviderEntryConfig(
                            providedVariables = mapOf("includeDetails" to "! Boolean"),
                            providerVariablesAPIData = ProviderVariablesAPIData(
                                type = "fromArgument",
                                path = "detailed",
                            ),
                        ),
                    ),
                ),
                querySelections = SelectionsBlockConfig(selections = "fragment _ on Query { viewer { id } }"),
                tenantAPIData = mapOf("resolverClass" to "example.TitleResolver"),
            ),
        ),
        bootstrapClass = "example.TodoBootstrapper",
        namedFragments = listOf("fragment TodoFields on Todo { id title }"),
    )
}
