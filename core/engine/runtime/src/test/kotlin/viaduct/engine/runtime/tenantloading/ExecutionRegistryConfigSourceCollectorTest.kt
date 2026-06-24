package viaduct.engine.runtime.tenantloading

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.api.bootstrap.executionregistry.ExecutionRegistryConfigFile

class ExecutionRegistryConfigSourceCollectorTest {
    @Test
    fun `fromResources returns matching registry config sources`() {
        val tenantNames = ExecutionRegistryConfigSourceCollector.fromResources("com.example")
            .map { source -> source.openStream().use { objectMapper.readValue<ExecutionRegistryConfigFile>(it).tenantName } }
            .toSet()

        assertEquals(
            setOf(
                "bootstrapped",
                "test",
                "unknown",
            ),
            tenantNames,
        )
    }

    @Test
    fun `fromResources without prefix returns registry config sources`() {
        val tenantNames = ExecutionRegistryConfigSourceCollector.fromResources()
            .mapNotNull { source -> source.openStream().use { objectMapper.readValue<ExecutionRegistryConfigFile>(it).tenantName } }

        assertTrue("test" in tenantNames)
    }

    @Test
    fun `fromResources returns empty list for unmatched prefix`() {
        assertEquals(emptyList<String>(), ExecutionRegistryConfigSourceCollector.fromResources("com.nomatch"))
    }

    companion object {
        private val objectMapper = jacksonObjectMapper()
    }
}
