package viaduct.engine.runtime.tenantloading

import java.net.URLClassLoader
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import viaduct.bootstrap.ViaductResourceIndexes

class ExecutionRegistryConfigSourceCollectorTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `fromResources loads exact resources from the generated index`() {
        val matchingPath = "META-INF/viaduct/modules/com.example.indexed.json"
        val unrelatedPath = "META-INF/viaduct/modules/com.other.unrelated.json"
        writeResource(ViaductResourceIndexes.EXECUTION_REGISTRY, "$matchingPath\n$unrelatedPath\n")
        writeResource(matchingPath, registryJson("indexed"))
        writeResource(unrelatedPath, registryJson("unrelated"))

        URLClassLoader(arrayOf(tempDir.toUri().toURL()), null).use { classLoader ->
            val sources = ExecutionRegistryConfigSourceCollector.fromResources("com.example", classLoader)

            assertEquals(listOf("indexed"), sources.map { it.tenantName })
        }
    }

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

    private fun writeResource(
        path: String,
        contents: String,
    ) {
        tempDir.resolve(path).apply {
            parent.createDirectories()
            writeText(contents)
        }
    }

    private fun registryJson(tenantName: String): String =
        """
        {
          "version": "1",
          "executorFactory": "com.example.ExecutorFactory",
          "tenantName": "$tenantName",
          "apiName": "kotlin",
          "nodes": [],
          "fields": []
        }
        """.trimIndent()
}
