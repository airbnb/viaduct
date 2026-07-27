package viaduct.engine.runtime.tenantloading

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExecutionRegistryConfigSourceCollectorTest {
    @Test
    fun `fromResources returns matching module config sources`() {
        val tenantNames = ExecutionRegistryConfigSourceCollector.fromResources("com.example")
            .map { it.tenantName }
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
    fun `fromResources without prefix returns module config sources`() {
        val tenantNames = ExecutionRegistryConfigSourceCollector.fromResources().map { it.tenantName }

        assertTrue("test" in tenantNames)
    }

    @Test
    fun `fromResources returns empty list for unmatched prefix`() {
        assertEquals(emptyList<String>(), ExecutionRegistryConfigSourceCollector.fromResources("com.nomatch"))
    }
}
